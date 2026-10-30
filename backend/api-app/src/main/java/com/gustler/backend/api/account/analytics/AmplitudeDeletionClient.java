package com.gustler.backend.api.account.analytics;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

public final class AmplitudeDeletionClient {
    private static final String DELETION_URI = "https://amplitude.com/api/2/deletions/users";
    private final RestClient client;
    private final AmplitudeDeletionProperties properties;

    public AmplitudeDeletionClient(RestClient client, AmplitudeDeletionProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public void delete(List<UUID> publicIds) {
        ResponseEntity<Void> response = client.post().uri(DELETION_URI)
            .headers(headers -> headers.setBasicAuth(properties.apiKey(), properties.secretKey()))
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("user_ids", publicIds.stream().map(UUID::toString).toList(), "ignore_invalid_id", true))
            .retrieve().toBodilessEntity();
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Amplitude 삭제 요청이 접수되지 않았다");
        }
    }
}
