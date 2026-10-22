package com.gustler.backend.api.account.unlink;

import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 어드민 키로 카카오 사용자의 앱 연결을 끊는다.
 *
 * <p>대상은 카카오 회원번호이며 ID 토큰의 {@code sub} 를 그대로 쓴다. 카카오가 "이미 연결되지 않은 사용자"(오류 코드
 * {@code -101})로 답하면 이미 끊긴 것이므로 성공으로 보되 따로 알린다. 다시 시도해도 같은 결과가 나오는 요청을 실패로 쌓지
 * 않기 위해서다.
 */
public final class KakaoUnlinkClient {
    private static final int NOT_LINKED = -101;

    private final RestClient restClient;
    private final KakaoUnlinkProperties properties;
    private final ObjectMapper objectMapper;

    public KakaoUnlinkClient(RestClient restClient, KakaoUnlinkProperties properties, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public UnlinkResult unlink(String subject) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("target_id_type", "user_id");
        form.add("target_id", subject);
        return restClient.post()
            .uri(properties.unlinkUri())
            .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + properties.adminKey())
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .exchange((request, response) -> resultOf(
                response.getStatusCode(), new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    private UnlinkResult resultOf(HttpStatusCode status, String body) {
        if (status.is2xxSuccessful()) {
            return UnlinkResult.done();
        }
        final int code = kakaoCodeOf(body);
        if (status.isSameCodeAs(HttpStatus.BAD_REQUEST) && code == NOT_LINKED) {
            return UnlinkResult.notLinked();
        }
        return UnlinkResult.failed("카카오가 HTTP " + status.value() + ", code " + code + " 로 거절했다");
    }

    private int kakaoCodeOf(String body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            return node.path("code").asInt(0);
        } catch (RuntimeException exception) {
            return 0;
        }
    }
}
