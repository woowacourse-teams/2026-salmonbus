package com.gustler.backend.api.account.analytics;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AmplitudeDeletionClientTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final AmplitudeDeletionClient client = new AmplitudeDeletionClient(builder.build(),
        new AmplitudeDeletionProperties("key", "secret"));

    @Test
    void 공개_ID와_프로젝트_인증을_보내고_기록이_없는_회원도_접수한다() {
        server.expect(requestTo("https://amplitude.com/api/2/deletions/users"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Basic a2V5OnNlY3JldA=="))
            .andExpect(content().json("""
                {"user_ids":["00000000-0000-0000-0000-000000000001"],"ignore_invalid_id":true}
                """))
            .andRespond(withSuccess());

        client.delete(List.of(ID));

        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 400, 401, 429, 500})
    void 접수되지_않은_응답은_실패로_알린다(int status) {
        server.expect(anything()).andRespond(withStatus(HttpStatusCode.valueOf(status)));

        assertThatThrownBy(() -> client.delete(List.of(ID))).isInstanceOf(RuntimeException.class);

        server.verify();
    }

    @Test
    void 연결_실패도_호출자에게_알린다() {
        server.expect(anything()).andRespond(withException(new java.io.IOException("connection failed")));

        assertThatThrownBy(() -> client.delete(List.of(ID))).isInstanceOf(RuntimeException.class);
    }
}
