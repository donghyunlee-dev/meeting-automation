package com.meetingautomation;

import com.meetingautomation.document.bootstrap.*;
import com.meetingautomation.document.DocumentProviderException;
import com.meetingautomation.document.settings.SettingsException;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.http.*;
import org.springframework.mock.http.client.*;
import org.springframework.test.web.client.*;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class DocumentBootstrapProviderTests {
    @Test void bootNotionHttpUsesPagePayloadParentAndInitialMarkerOnly() {
        var fixture=new HttpPages(true,null);
        var structure=new DocumentStructureInitializer(fixture.pages).initializeStructure(fixture.parent,null,"operation",new DocumentStructureInitializerTests.Journal());
        assertThat(fixture.posts).hasSize(4);
        assertThat(fixture.posts.getFirst().path("parent").path("page_id").asText()).isEqualTo(fixture.parent);
        assertThat(fixture.posts.get(1).path("parent").path("page_id").asText()).isEqualTo(structure.rootId());
        assertThat(fixture.posts).allSatisfy(body -> assertThat(body.toString()).contains("meeting-automation/bootstrap/operation/").doesNotContain("database_id","data_source_id"));
        assertThat(fixture.posts.get(3).toString()).contains("Templates", "AI 작성 지침", "회의 개요", "확인이 필요한 사항", "비고");
        fixture.server.verify();
    }
    @ParameterizedTest @ValueSource(strings={"22","TOP"})
    void bootConfluenceHttpUsesSpaceAndOptionalParentAndOnlyPageCreates(String parent) {
        var fixture=new HttpPages(false,"TOP".equals(parent)?null:parent);
        var structure=new DocumentStructureInitializer(fixture.pages).initializeStructure(fixture.parent,null,"operation",new DocumentStructureInitializerTests.Journal());
        assertThat(fixture.posts).hasSize(4);
        assertThat(fixture.posts).allSatisfy(body -> assertThat(body.path("spaceId").asText()).isEqualTo("1"));
        assertThat(fixture.posts.getFirst().path("parentId").asText(null)).isEqualTo(fixture.parent);
        assertThat(fixture.posts.get(1).path("parentId").asText()).isEqualTo(structure.rootId());
        assertThat(fixture.posts.getFirst().path("body").path("value").asText()).contains("meeting-automation/bootstrap/operation/ROOT");
        assertThat(fixture.posts.get(3).path("body").path("value").asText())
            .contains("<h1>기본 회의록 템플릿</h1>", "<h2>AI 작성 지침</h2>", "<h2>회의 개요</h2>", "<h2>비고</h2>")
            .contains("<ul><li>주제: 회의 제목</li></ul>");
        fixture.server.verify();
    }
    @Test void bootConfluenceCqlPaginationDetectsWrongTypeRootBeforeAnyCreate() {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://site.atlassian.net/wiki/api/v2/spaces/1")).andRespond(withSuccess("{\"id\":\"1\",\"key\":\"TEST\"}",MediaType.APPLICATION_JSON));
        server.expect(request -> {
            String query=java.net.URLDecoder.decode(request.getURI().getRawQuery(),StandardCharsets.UTF_8);
            assertThat(query).contains("space = \"TEST\"","title ~ \"\\\"Meeting Automation\\\"\"","type IN (page, folder, database)");
            assertThat(request.getMethod()).isEqualTo(HttpMethod.GET);
        }).andRespond(withSuccess("{\"results\":[],\"_links\":{\"next\":\"/wiki/rest/api/search?cursor=second\"}}",MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://site.atlassian.net/wiki/rest/api/search?cursor=second"))
            .andRespond(withSuccess("{\"results\":[{\"content\":{\"id\":\"9\",\"title\":\"Meeting Automation\",\"type\":\"folder\",\"space\":{\"id\":\"1\"},\"ancestors\":[]}}]}",MediaType.APPLICATION_JSON));
        var pages=new ConfluenceBootstrapPages(builder,"https://site.atlassian.net","1","synthetic@example.test","synthetic-secret");
        assertThatThrownBy(() -> new DocumentStructureInitializer(pages).initializeStructure(null,null,"operation",new DocumentStructureInitializerTests.Journal()))
            .isInstanceOf(SettingsException.class).extracting("code").isEqualTo("DOCUMENT_STRUCTURE_CONFLICT");
        server.verify();
    }
    @Test void bootConfluenceRootSearchUsesCompatibleExactPhraseAndReturnsExistingRoot() {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
        var captured=new java.util.concurrent.atomic.AtomicReference<java.net.URI>();
        server.expect(requestTo("https://site.atlassian.net/wiki/api/v2/spaces/1"))
            .andRespond(withSuccess("{\"id\":\"1\",\"key\":\"TEST\"}",MediaType.APPLICATION_JSON));
        server.expect(request -> { captured.set(request.getURI()); assertThat(request.getMethod()).isEqualTo(HttpMethod.GET); })
            .andRespond(withSuccess("{\"results\":[{\"content\":{\"id\":\"9\",\"title\":\"Meeting Automation\",\"type\":\"page\",\"space\":{\"id\":\"1\"},\"ancestors\":[]}}]}",MediaType.APPLICATION_JSON));
        var pages=new ConfluenceBootstrapPages(builder,"https://site.atlassian.net","1","synthetic@example.test","synthetic-secret");
        assertThat(pages.children(null)).containsExactly(new BootstrapPages.Page("9",null,"Meeting Automation","page",null));
        String rawCql=Arrays.stream(captured.get().getRawQuery().split("&")).filter(parameter -> parameter.startsWith("cql="))
            .findFirst().orElseThrow().substring(4);
        String decodedCql=java.net.URLDecoder.decode(rawCql,StandardCharsets.UTF_8);
        assertThat(decodedCql).isEqualTo("space = \"TEST\" AND title ~ \"\\\"Meeting Automation\\\"\" AND type IN (page, folder, database)");
        assertThat(rawCql).contains("title+%7E+").contains("%5C%22Meeting+Automation%5C%22").doesNotContain("%255C","%2522");
        server.verify();
    }
    @Test void bootConfluenceCompleteExplicitRootReuseMakesNoCreateRequests() {
        var fixture=new HttpPages(false,null);
        fixture.nodes.put("9",new BootstrapPages.Page("9",null,"Meeting Automation","page",null));
        fixture.nodes.put("10",new BootstrapPages.Page("10","9","Meetings","page",null));
        fixture.nodes.put("11",new BootstrapPages.Page("11","9","Participants","page",null));
        fixture.nodes.put("12",new BootstrapPages.Page("12","9","Templates","page",null));
        var structure=new DocumentStructureInitializer(fixture.pages).initializeStructure(null,"9","operation",new DocumentStructureInitializerTests.Journal());
        assertThat(structure.rootId()).isEqualTo("9");
        assertThat(structure.meetingsPageId()).isEqualTo("10");
        assertThat(structure.participantsPageId()).isEqualTo("11");
        assertThat(fixture.posts).isEmpty(); fixture.server.verify();
    }
    @Test void bootConfluenceNeverForwardsCredentialsToPaginationOrigin() {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://site.atlassian.net/wiki/api/v2/pages/2/direct-children?limit=100"))
            .andRespond(withSuccess("{\"results\":[],\"_links\":{\"next\":\"https://evil.example/wiki/api/v2/pages/2/direct-children\"}}",MediaType.APPLICATION_JSON));
        var pages=new ConfluenceBootstrapPages(builder,"https://site.atlassian.net","1","synthetic@example.test","synthetic-secret");
        assertThatThrownBy(() -> pages.children("2")).isInstanceOf(DocumentProviderException.class)
            .extracting("retryable").isEqualTo(false);
        server.verify();
    }
    @Test void bootConfluenceExplicitFolderReuseIsStructureConflictWithoutPageReadOrWrite() {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://site.atlassian.net/wiki/api/v2/spaces/1"))
            .andRespond(withSuccess("{\"id\":\"1\",\"key\":\"TEST\"}",MediaType.APPLICATION_JSON));
        server.expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/wiki/rest/api/search"))
            .andRespond(withSuccess("{\"results\":[{\"content\":{\"id\":\"9\",\"title\":\"Meeting Automation\",\"type\":\"folder\",\"space\":{\"id\":\"1\"},\"ancestors\":[]}}]}",MediaType.APPLICATION_JSON));
        var pages=new ConfluenceBootstrapPages(builder,"https://site.atlassian.net","1","synthetic@example.test","synthetic-secret");
        assertThatThrownBy(() -> new DocumentStructureInitializer(pages).initializeStructure(null,"9","operation",new DocumentStructureInitializerTests.Journal()))
            .isInstanceOf(SettingsException.class).extracting("code").isEqualTo("DOCUMENT_STRUCTURE_CONFLICT");
        server.verify();
    }
    @Test void bootProviderRedirectFailsSafelyWithoutRetryOrRawResponse() {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.notion.com/v1/pages")).andExpect(method(HttpMethod.POST))
            .andRespond(withStatus(HttpStatus.FOUND).header("Location","https://evil.example").body("synthetic-secret provider-original"));
        assertThatThrownBy(() -> new NotionBootstrapPages(builder,"synthetic-secret").create("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","Meeting Automation","meeting-automation/bootstrap/operation/ROOT"))
            .isInstanceOf(DocumentProviderException.class).hasMessageNotContaining("synthetic").hasNoCause()
            .extracting("retryable").isEqualTo(false);
        server.verify();
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void bootProviderUnknownOutcomeSeparatesWriteReconciliationFromReadFailure(boolean write) {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(request -> assertThat(request.getMethod()).isEqualTo(write?HttpMethod.POST:HttpMethod.GET))
            .andRespond(request -> { throw new java.net.SocketTimeoutException("synthetic-secret provider-original"); });
        var pages=new NotionBootstrapPages(builder,"synthetic-secret");
        var failure=catchThrowable(() -> {
            if(write) pages.create("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","Meeting Automation","meeting-automation/bootstrap/operation/ROOT");
            else pages.children("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        });
        assertThat(failure).hasMessageNotContaining("synthetic").hasNoCause();
        if(write) assertThat(failure).isInstanceOf(SettingsException.class).extracting("code").isEqualTo("DOCUMENT_RECONCILIATION_REQUIRED");
        else assertThat(failure).isInstanceOf(DocumentProviderException.class).extracting("retryable").isEqualTo(false);
        server.verify();
    }
    @ParameterizedTest @ValueSource(ints={400,401,403,429,503})
    void bootProviderHttpStatusRetainsOnlySafeRetryability(int status) {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.notion.com/v1/pages")).andExpect(method(HttpMethod.POST))
            .andRespond(withStatus(HttpStatus.valueOf(status)).body("synthetic-secret provider-original"));
        assertThatThrownBy(() -> new NotionBootstrapPages(builder,"synthetic-secret")
            .create("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","Meeting Automation","meeting-automation/bootstrap/operation/ROOT"))
            .isInstanceOf(DocumentProviderException.class).hasMessageNotContaining("synthetic").hasNoCause()
            .extracting("retryable").isEqualTo(status==429 || status>=500);
        server.verify();
    }
    static java.util.stream.Stream<Arguments> ambiguousCreateResponses() {
        return java.util.stream.Stream.of("EMPTY","null","[]","{}","{\"id\":\"invalid\"}")
            .flatMap(response -> java.util.stream.Stream.of(true,false).map(notion -> Arguments.of(notion,response)));
    }
    @ParameterizedTest @MethodSource("ambiguousCreateResponses")
    void bootRecoveryUnusableSuccessResponseRequiresReconciliation(boolean notion,String response) {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.POST)).andRespond("EMPTY".equals(response)
            ? withStatus(HttpStatus.NO_CONTENT) : withSuccess(response,MediaType.APPLICATION_JSON));
        BootstrapPages pages=notion?new NotionBootstrapPages(builder,"synthetic-secret")
            :new ConfluenceBootstrapPages(builder,"https://site.atlassian.net","1","synthetic@example.test","synthetic-secret");
        assertThatThrownBy(() -> pages.create(notion?"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee":"22","Meeting Automation","meeting-automation/bootstrap/operation/ROOT"))
            .isInstanceOf(SettingsException.class).extracting("code").isEqualTo("DOCUMENT_RECONCILIATION_REQUIRED");
        server.verify();
    }
    @ParameterizedTest @ValueSource(strings={"null","[]","{}"})
    void bootProviderUnusableReadResponseDoesNotExposeRetry(String response) {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.GET)).andRespond(withSuccess(response,MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> new NotionBootstrapPages(builder,"synthetic-secret").children("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
            .isInstanceOf(DocumentProviderException.class).extracting("retryable").isEqualTo(false);
        server.verify();
    }
    private static final class HttpPages {
        private static final JsonMapper JSON=JsonMapper.builder().build();
        final boolean notion; final String parent; final BootstrapPages pages; final MockRestServiceServer server;
        final Map<String,BootstrapPages.Page> nodes=new LinkedHashMap<>(); final List<tools.jackson.databind.JsonNode> posts=new ArrayList<>();
        HttpPages(boolean notion,String parent) {
            this.notion=notion; this.parent=notion?"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee":parent;
            var builder=RestClient.builder(); server=MockRestServiceServer.bindTo(builder).build();
            server.expect(ExpectedCount.manyTimes(),request -> {
                assertThat(request.getHeaders().getFirst("Authorization")).isNotBlank();
                assertThat(request.getURI().getHost()).isEqualTo(notion?"api.notion.com":"site.atlassian.net");
                assertThat(request.getMethod()).isIn(HttpMethod.GET,HttpMethod.POST);
                if (request.getMethod()==HttpMethod.POST) assertThat(request.getURI().getPath()).isEqualTo(notion?"/v1/pages":"/wiki/api/v2/pages");
            }).andRespond(request -> {
                String path=request.getURI().getPath(); Object body;
                if(request.getMethod()==HttpMethod.POST) {
                    var payload=JSON.readTree(((MockClientHttpRequest)request).getBodyAsString()); posts.add(payload);
                    String id=notion?String.format("%08d-0000-0000-0000-000000000000",posts.size()):Integer.toString(posts.size()+100);
                    String nodeParent=notion?payload.path("parent").path("page_id").asText():payload.path("parentId").asText(null);
                    String title=notion?payload.path("properties").path("title").path("title").get(0).path("text").path("content").asText():payload.path("title").asText();
                    String marker;
                    if (notion) marker=payload.path("children").get(0).path("paragraph").path("rich_text").get(0).path("text").path("content").asText();
                    else {
                        var markerMatcher=java.util.regex.Pattern.compile("<p>(meeting-automation/bootstrap/[A-Za-z0-9_\\-/]+)</p>").matcher(payload.path("body").path("value").asText());
                        marker=markerMatcher.find()?markerMatcher.group(1):"";
                    }
                    if(!notion && nodeParent==null) assertThat(request.getURI().getQuery()).isEqualTo("root-level=true");
                    nodes.put(id,new BootstrapPages.Page(id,nodeParent,title,"page",marker)); body=Map.of("id",id);
                } else if(path.equals("/wiki/api/v2/spaces/1")) body=Map.of("id","1","key","TEST");
                else if(path.equals("/wiki/rest/api/search")) {
                    body=Map.of("results",nodes.values().stream().filter(p -> p.parentId()==null).map(p -> Map.of("content",Map.of("id",p.id(),"title",p.title(),"type","page","space",Map.of("id","1"),"ancestors",List.of()))).toList());
                } else if(path.endsWith("/children") || path.endsWith("/direct-children")) {
                    String id=path.split("/")[notion?3:5];
                    List<Map<String,Object>> children=new ArrayList<>();
                    if(notion && nodes.containsKey(id)) children.add(Map.of("type","paragraph","paragraph",Map.of("rich_text",List.of(Map.of("plain_text",nodes.get(id).marker())))));
                    for(var p:nodes.values()) if(Objects.equals(id,p.parentId())) children.add(notion
                        ?Map.of("id",p.id(),"type","child_page","child_page",Map.of("title",p.title()))
                        :Map.of("id",p.id(),"type","page","title",p.title(),"parentId",id));
                    body=notion?Map.of("results",children,"has_more",false):Map.of("results",children);
                } else {
                    String id=path.substring(path.lastIndexOf('/')+1); var p=nodes.get(id);
                    if(p==null) throw new AssertionError("Unknown fixture page");
                    if(notion) body=Map.of("id",id,"object","page","parent",Map.of("page_id",p.parentId()),
                        "properties",Map.of("title",Map.of("type","title","title",List.of(Map.of("plain_text",p.title())))));
                    else {
                        Map<String,Object> result=new LinkedHashMap<>(Map.of("id",id,"title",p.title(),"spaceId","1","status","current",
                            "body",Map.of("storage",Map.of("value","<p local-id=\"fixture\">"+p.marker()+"</p>"))));
                        if(p.parentId()!=null) { result.put("parentId",p.parentId()); result.put("parentType","page"); }
                        body=result;
                    }
                }
                var response=new MockClientHttpResponse(JSON.writeValueAsBytes(body),HttpStatus.OK);
                response.getHeaders().setContentType(MediaType.APPLICATION_JSON); return response;
            });
            pages=notion?new NotionBootstrapPages(builder,"synthetic-secret"):new ConfluenceBootstrapPages(builder,"https://site.atlassian.net","1","synthetic@example.test","synthetic-secret");
        }
    }
}
