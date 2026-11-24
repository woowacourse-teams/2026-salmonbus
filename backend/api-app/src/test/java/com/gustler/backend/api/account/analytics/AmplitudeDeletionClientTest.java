package com.gustler.backend.api.account.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@ExtendWith(OutputCaptureExtension.class)
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
                {"user_ids":["00000000-0000-0000-0000-000000000001"],"ignore_invalid_id":"True"}
                """))
            .andExpect(jsonPath("$.ignore_invalid_id").isString())
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

    @Test
    void 거절_사유를_상태_코드와_함께_남긴다(CapturedOutput output) {
        // given
        server.expect(anything()).andRespond(withStatus(HttpStatusCode.valueOf(400))
            .contentType(MediaType.APPLICATION_JSON)
            .body("""
                {"error":{"http_code":400,"type":"invalid","message":"Invalid chart definition",
                "metadata":{"details":"ignore_invalid_id can only be 'True' or 'False'"}}}
                """));

        // when
        assertThatThrownBy(() -> client.delete(List.of(ID))).isInstanceOf(RuntimeException.class);

        // then
        assertThat(output.getAll()).contains("status=400", "ignore_invalid_id can only be 'True' or 'False'");
    }

    @Test
    void 거절_사유에_회원_ID와_키가_있으면_가린다(CapturedOutput output) {
        // given
        String otherId = "A1B2C3D4-0000-4000-8000-00000000000F";
        String projectKey = "0123456789abcdef0123456789abcdef";
        server.expect(anything()).andRespond(withStatus(HttpStatusCode.valueOf(400))
            .contentType(MediaType.APPLICATION_JSON)
            .body("""
                {"error":{"metadata":{"details":"rejected %s %s %s 123456789 member@example.com secret a2V5OnNlY3JldA==\\nforged"}},
                "user_ids":["raw-body-marker"]}
                """.formatted(ID, otherId, projectKey)));

        // when
        assertThatThrownBy(() -> client.delete(List.of(ID))).isInstanceOf(RuntimeException.class);

        // then
        assertThat(output.getAll()).contains("status=400", "rejected")
            .doesNotContain(ID.toString(), otherId, projectKey, "123456789", "member@example.com", "secret",
                "a2V5OnNlY3JldA==", "raw-body-marker", "\nforged");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-json-00000000-0000-0000-0000-000000000001", "[]", "{\"error\":\"text\"}"})
    void 사유를_읽지_못하면_본문_없이_상태_코드만_남긴다(String body, CapturedOutput output) {
        // given
        server.expect(anything()).andRespond(withStatus(HttpStatusCode.valueOf(400)).body(body));

        // when
        assertThatThrownBy(() -> client.delete(List.of(ID))).isInstanceOf(RuntimeException.class);

        // then
        assertThat(output.getAll()).contains("status=400 reason=UNKNOWN").doesNotContain("not-json", ID.toString());
    }
}
