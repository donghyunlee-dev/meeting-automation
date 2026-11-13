package com.meetingautomation.document.notion;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
class NotionMeetingDeletionTests {
 private static final JsonMapper JSON=JsonMapper.builder().build();
 private static final String HUMAN="11111111-1111-1111-1111-111111111111";
 @Test void deletesInOrder() { Fake a=new Fake(); delete(a); assertThat(a.deleted).containsExactly(HUMAN,"raw","row"); }
 @Test void rejectsForeignHumanBeforeMutation() { Fake a=new Fake(); a.humanParent="foreign"; assertThatThrownBy(()->delete(a)); assertThat(a.deleted).isEmpty(); }
 @Test void rejectsForeignRawBeforeMutation() { Fake a=new Fake(); a.rawParent="foreign"; assertThatThrownBy(()->delete(a)); assertThat(a.deleted).isEmpty(); }
 @Test void retryCompletesPartialFailure() { Fake a=new Fake(); a.fail=true; assertThatThrownBy(()->delete(a)); assertThat(a.deleted).containsExactly(HUMAN); a.fail=false; delete(a); assertThat(a.deleted).containsExactly(HUMAN,"raw","row"); }
 @Test void unknownMeetingNeverReadsPage() { Fake a=new Fake(); assertThatThrownBy(()->new NotionMeetingDeletion(a).delete("other","data","meetings","index")); assertThat(a.reads).isZero(); }
 private void delete(Fake a) { new NotionMeetingDeletion(a).delete("raw","data","meetings","index"); }
 static class Fake implements NotionMeetingDeletion.Access {
  List<String> deleted=new ArrayList<>(); String rawParent="data",humanParent="meetings"; boolean fail; int reads;
  public List<JsonNode> blocks(String id) {
   if(id.equals("index")) return List.of(JSON.readTree("{\"id\":\"table\",\"type\":\"table\"}"));
   List<Object> cells=List.of("raw","session","Title","2026-11-13T10:00:00Z","[]","https://www.notion.so/"+HUMAN.replace("-", "")).stream().map(v->(Object)List.of(Map.of("plain_text",v))).toList();
   return List.of(JSON.readTree("{\"type\":\"table_row\"}"),JSON.valueToTree(Map.of("id","row","type","table_row","table_row",Map.of("cells",cells))));
  }
  public JsonNode page(String id) { reads++; return JSON.valueToTree(Map.of("id",id,"parent",Map.of("type","page_id","page_id",id.equals("raw")?rawParent:humanParent),"archived",deleted.contains(id))); }
  public void archive(String id,boolean page) { if(id.equals("raw")&&fail) throw new IllegalStateException(); deleted.add(id); }
 }
}
