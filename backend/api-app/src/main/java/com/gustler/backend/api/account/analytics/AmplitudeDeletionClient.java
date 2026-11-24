package com.gustler.backend.api.account.analytics;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

public final class AmplitudeDeletionClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(AmplitudeDeletionClient.class);
    private static final String DELETION_URI = "https://amplitude.com/api/2/deletions/users";
    private static final String UNKNOWN_REASON = "UNKNOWN";
    private static final String MASK = "***";
    private static final int MAX_REASON_LENGTH = 200;
    private static final Pattern SENSITIVE = Pattern.compile(
        "\\S+@\\S+|(?=[A-Za-z0-9+/=_-]*[0-9])[A-Za-z0-9+/=_-]{16,}|[0-9]{6,}");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}+");
    private final RestClient client;
    private final AmplitudeDeletionProperties properties;

    public AmplitudeDeletionClient(RestClient client, AmplitudeDeletionProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /** Amplitude는 {@code ignore_invalid_id}를 JSON 불리언이 아니라 문자열 True, False로만 받는다. */
    public void delete(List<UUID> publicIds) {
        ResponseEntity<Void> response = send(publicIds);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Amplitude 삭제 요청이 접수되지 않았다");
        }
    }

    private ResponseEntity<Void> send(List<UUID> publicIds) {
        try {
            return client.post().uri(DELETION_URI)
                .headers(headers -> headers.setBasicAuth(properties.apiKey(), properties.secretKey()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("user_ids", publicIds.stream().map(UUID::toString).toList(), "ignore_invalid_id", "True"))
                .retrieve().toBodilessEntity();
        } catch (RestClientResponseException exception) {
            LOGGER.warn("Amplitude가 삭제 요청을 거절했다. status={} reason={}",
                exception.getStatusCode().value(), reasonOf(exception));
            throw exception;
        }
    }

    /** 응답 본문에는 회원 ID가 되돌아올 수 있어 사유 필드만 꺼내 가린다. */
    private String reasonOf(RestClientResponseException exception) {
        try {
            String details = JsonMapper.shared().readTree(exception.getResponseBodyAsString())
                .path("error").path("metadata").path("details").asString("");
            if (details.isBlank()) {
                return UNKNOWN_REASON;
            }
            return mask(details);
        } catch (JacksonException unreadable) {
            return UNKNOWN_REASON;
        }
    }

    private String mask(String details) {
        String withoutKeys = details.replace(properties.apiKey(), MASK).replace(properties.secretKey(), MASK);
        String masked = SENSITIVE.matcher(CONTROL.matcher(withoutKeys).replaceAll(" ")).replaceAll(MASK);
        return masked.substring(0, Math.min(masked.length(), MAX_REASON_LENGTH));
    }
}
