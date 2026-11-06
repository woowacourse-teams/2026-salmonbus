package com.gustler.backend.api.push.delivery;

import com.gustler.backend.api.favorite.domain.FavoriteTarget;
import com.gustler.backend.api.push.application.NotificationDeliveryRepository.ClaimedDelivery;
import com.gustler.backend.api.push.domain.DeliveryOutcome;
import com.gustler.backend.api.push.domain.PushEnvironment;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.ObjectMapper;

final class ApnsClient implements ApnsSender {
    private static final Pattern REASON = Pattern.compile("[A-Za-z]{1,64}");

    private final HttpClient http;
    private final URI productionUri;
    private final URI sandboxUri;
    private final String topic;
    private final ApnsProviderToken providerToken;
    private final ObjectMapper objectMapper;
    private final Duration requestTimeout;

    ApnsClient(
        HttpClient http,
        URI productionUri,
        URI sandboxUri,
        String topic,
        ApnsProviderToken providerToken,
        ObjectMapper objectMapper,
        Duration requestTimeout
    ) {
        this.http = http;
        this.productionUri = productionUri;
        this.sandboxUri = sandboxUri;
        this.topic = topic;
        this.providerToken = providerToken;
        this.objectMapper = objectMapper;
        this.requestTimeout = requestTimeout;
    }

    @Override
    public ApnsResponse send(ClaimedDelivery delivery) {
        String used = providerToken.current();
        URI base = delivery.environment() == PushEnvironment.PRODUCTION ? productionUri : sandboxUri;
        HttpRequest request = HttpRequest.newBuilder(base.resolve("/3/device/" + delivery.token()))
            .timeout(requestTimeout)
            .header("authorization", "bearer " + used)
            .header("apns-topic", topic)
            .header("apns-push-type", "alert")
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload(delivery)))
            .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            String reason = reasonOf(response.statusCode(), response.body());
            DeliveryOutcome outcome = classify(response.statusCode(), reason);
            if (outcome == DeliveryOutcome.RETRYABLE && "ExpiredProviderToken".equals(reason)) {
                providerToken.invalidate(used);
            }
            return new ApnsResponse(outcome, reason);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new ApnsResponse(DeliveryOutcome.RETRYABLE, exception.getClass().getSimpleName());
        } catch (IOException exception) {
            return new ApnsResponse(DeliveryOutcome.RETRYABLE, exception.getClass().getSimpleName());
        }
    }

    private String payload(ClaimedDelivery delivery) {
        FavoriteTarget target = delivery.target();
        return objectMapper.writeValueAsString(Map.of(
            "aps", Map.of(
                "alert", Map.of(
                    "title", "연어버스",
                    "body", "즐겨찾기한 버스를 확인할 시간이에요"
                ),
                "sound", "default"
            ),
            "target", Map.of(
                "routeId", target.routeId(),
                "stopId", target.stopId(),
                "direction", target.direction().name()
            )
        ));
    }

    private String reasonOf(int status, String body) {
        try {
            String reason = objectMapper.readTree(body).path("reason").asText("");
            if (REASON.matcher(reason).matches()) {
                return reason;
            }
        } catch (RuntimeException exception) {
            return "HTTP_" + status;
        }
        return "HTTP_" + status;
    }

    private static DeliveryOutcome classify(int status, String reason) {
        if (status == 200) {
            return DeliveryOutcome.SENT;
        }
        if (status == 410) {
            return DeliveryOutcome.TOKEN_REJECTED;
        }
        if (status == 400 && "BadDeviceToken".equals(reason)) {
            return DeliveryOutcome.TOKEN_REJECTED;
        }
        if (status == 403 && "ExpiredProviderToken".equals(reason)) {
            return DeliveryOutcome.RETRYABLE;
        }
        if (status == 429 || status >= 500) {
            return DeliveryOutcome.RETRYABLE;
        }
        return DeliveryOutcome.CONFIGURATION_ERROR;
    }
}
