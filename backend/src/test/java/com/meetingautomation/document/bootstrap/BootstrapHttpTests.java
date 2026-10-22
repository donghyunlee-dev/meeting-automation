package com.meetingautomation.document.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class BootstrapHttpTests {
    @Test
    void notionAppendFailureLogsSafeRequestContextWithoutProviderBody() {
        Logger logger = (Logger) LoggerFactory.getLogger(BootstrapHttp.class);
        ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("https://api.notion.com/v1/blocks/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/children"))
                    .andExpect(method(HttpMethod.PATCH))
                    .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                            .body("{\"code\":\"validation_error\",\"message\":\"synthetic-secret\"}"));
            var http = new BootstrapHttp(builder.build(), "https://api.notion.com");

            assertThatThrownBy(() -> http.patch("/v1/blocks/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/children",
                    Map.of("children", List.of())))
                    .hasMessageNotContaining("synthetic-secret");

            assertThat(appender.list).hasSize(1);
            String message = appender.list.getFirst().getFormattedMessage();
            assertThat(message).contains("provider=notion", "method=PATCH", "endpoint=block-children", "status=400")
                    .doesNotContain("synthetic-secret", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
            server.verify();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
