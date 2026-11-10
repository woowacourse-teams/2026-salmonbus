package com.gustler.backend.api.push.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.favorite.domain.FavoriteDirection;
import com.gustler.backend.api.favorite.domain.FavoriteTarget;
import com.gustler.backend.api.push.application.NotificationDeliveryRepository.ClaimedDelivery;
import com.gustler.backend.api.push.domain.DeliveryOutcome;
import com.gustler.backend.api.push.domain.PushEnvironment;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ApnsClientTest {
    private static final String TOKEN = "aabbccddeeff0011";
    private static final Instant START = Instant.parse("2026-11-01T00:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path directory;

    private MovingClock clock;
    private ApnsProviderToken providerToken;

    @BeforeEach
    void loadToken() throws Exception {
        clock = new MovingClock(START);
        Path keyFile = TestApnsKeys.writePkcs8Pem(
            TestApnsKeys.ecKeyPair("secp256r1"),
            directory.resolve("AuthKey.p8")
        );
        providerToken = ApnsProviderToken.load(new ApnsProperties(
            "TEAMTEST01",
            "KEYTEST001",
            keyFile.toString(),
            "com.salmonbus.app",
            "http://127.0.0.1:9",
            "http://127.0.0.1:9"
        ), clock);
    }

    @Test
    void sendsExpectedRequestAndPayload() throws Exception {
        try (FakeApnsServer server = new FakeApnsServer()) {
            ApnsResponse response = client(server.baseUri(), server.baseUri(), Duration.ofSeconds(5))
                .send(delivery(PushEnvironment.SANDBOX));

            assertThat(response.outcome()).isEqualTo(DeliveryOutcome.SENT);
            FakeApnsServer.Received received = server.requests().get(0);
            assertThat(received.token()).isEqualTo(TOKEN);
            assertThat(received.authorization()).startsWith("bearer ");
            assertThat(received.topic()).isEqualTo("com.salmonbus.app");
            assertThat(received.pushType()).isEqualTo("alert");
            JsonNode root = MAPPER.readTree(received.body());
            assertThat(root.path("aps").has("target")).isFalse();
            assertThat(root.path("aps").path("alert").path("title").asText()).isEqualTo("연어버스");
            assertThat(root.path("aps").path("alert").path("body").asText())
                .isEqualTo("즐겨찾기한 버스를 확인할 시간이에요");
            assertThat(root.path("aps").path("sound").asText()).isEqualTo("default");
            assertThat(root.path("target").path("routeId").asText()).isEqualTo("234000884");
            assertThat(root.path("target").path("stopId").asText()).isEqualTo("203000165");
            assertThat(root.path("target").path("direction").asText()).isEqualTo("UP");
        }
    }

    @Test
    void classifiesDocumentedResponses() throws Exception {
        try (FakeApnsServer server = new FakeApnsServer()) {
            ApnsClient client = client(server.baseUri(), server.baseUri(), Duration.ofSeconds(5));

            server.answerAll(410, "Unregistered");
            assertThat(client.send(delivery(PushEnvironment.SANDBOX)).outcome())
                .isEqualTo(DeliveryOutcome.TOKEN_REJECTED);

            server.answerAll(400, "BadDeviceToken");
            assertThat(client.send(delivery(PushEnvironment.SANDBOX)).outcome())
                .isEqualTo(DeliveryOutcome.TOKEN_REJECTED);

            server.answerAll(403, "ExpiredProviderToken");
            assertThat(client.send(delivery(PushEnvironment.SANDBOX)).outcome())
                .isEqualTo(DeliveryOutcome.RETRYABLE);

            server.answerAll(429, "TooManyRequests");
            assertThat(client.send(delivery(PushEnvironment.SANDBOX)).outcome())
                .isEqualTo(DeliveryOutcome.RETRYABLE);

            server.answerAll(500, "InternalServerError");
            assertThat(client.send(delivery(PushEnvironment.SANDBOX)).outcome())
                .isEqualTo(DeliveryOutcome.RETRYABLE);

            server.answerAll(503, "Shutdown");
            assertThat(client.send(delivery(PushEnvironment.SANDBOX)).outcome())
                .isEqualTo(DeliveryOutcome.RETRYABLE);

            server.answerAll(403, "InvalidProviderToken");
            assertThat(client.send(delivery(PushEnvironment.SANDBOX)).outcome())
                .isEqualTo(DeliveryOutcome.CONFIGURATION_ERROR);

            server.answerAll(400, "DeviceTokenNotForTopic");
            assertThat(client.send(delivery(PushEnvironment.SANDBOX)).outcome())
                .isEqualTo(DeliveryOutcome.CONFIGURATION_ERROR);

            server.answerAll(404, "BadPath");
            assertThat(client.send(delivery(PushEnvironment.SANDBOX)).outcome())
                .isEqualTo(DeliveryOutcome.CONFIGURATION_ERROR);

            server.answerAll(201, "Created");
            assertThat(client.send(delivery(PushEnvironment.SANDBOX)).outcome())
                .isEqualTo(DeliveryOutcome.CONFIGURATION_ERROR);
        }
    }

    @Test
    void nonJsonErrorBodyUsesHttpStatusReason() throws Exception {
        try (FakeApnsServer server = new FakeApnsServer()) {
            server.answerAll(400, null);
            ApnsResponse response = client(server.baseUri(), server.baseUri(), Duration.ofSeconds(5))
                .send(delivery(PushEnvironment.SANDBOX));
            assertThat(response.reason()).isEqualTo("HTTP_400");
            assertThat(response.outcome()).isEqualTo(DeliveryOutcome.CONFIGURATION_ERROR);
        }
    }

    @Test
    void connectionFailureIsRetryableWithoutToken() {
        ApnsResponse response = client(
            URI.create("http://127.0.0.1:9"),
            URI.create("http://127.0.0.1:9"),
            Duration.ofSeconds(5)
        ).send(delivery(PushEnvironment.PRODUCTION));
        assertThat(response.outcome()).isEqualTo(DeliveryOutcome.RETRYABLE);
        assertThat(response.reason()).doesNotContain(TOKEN);
    }

    @Test
    void timeoutIsRetryableWithoutToken() throws Exception {
        try (FakeApnsServer server = new FakeApnsServer()) {
            server.beforeResponse(() -> {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
            ApnsResponse response = client(server.baseUri(), server.baseUri(), Duration.ofMillis(50))
                .send(delivery(PushEnvironment.SANDBOX));
            assertThat(response.outcome()).isEqualTo(DeliveryOutcome.RETRYABLE);
            assertThat(response.reason()).doesNotContain(TOKEN);
        }
    }

    @Test
    void routesSandboxAndProductionToMatchingHosts() throws Exception {
        try (FakeApnsServer production = new FakeApnsServer();
             FakeApnsServer sandbox = new FakeApnsServer()) {
            ApnsClient client = client(production.baseUri(), sandbox.baseUri(), Duration.ofSeconds(5));

            client.send(delivery(PushEnvironment.PRODUCTION));
            client.send(delivery(PushEnvironment.SANDBOX));

            assertThat(production.requests()).hasSize(1);
            assertThat(sandbox.requests()).hasSize(1);
            assertThat(production.requests().get(0).token()).isEqualTo(TOKEN);
            assertThat(sandbox.requests().get(0).token()).isEqualTo(TOKEN);
        }
    }

    @Test
    void expiredProviderTokenChangesNextAuthorization() throws Exception {
        try (FakeApnsServer server = new FakeApnsServer()) {
            ApnsClient client = client(server.baseUri(), server.baseUri(), Duration.ofSeconds(5));
            server.answerAll(403, "ExpiredProviderToken");
            assertThat(client.send(delivery(PushEnvironment.SANDBOX)).outcome())
                .isEqualTo(DeliveryOutcome.RETRYABLE);
            clock.set(START.plusSeconds(1));
            server.answerAll(200, null);
            assertThat(client.send(delivery(PushEnvironment.SANDBOX)).outcome())
                .isEqualTo(DeliveryOutcome.SENT);
            assertThat(server.requests()).hasSize(2);
            assertThat(server.requests().get(0).authorization())
                .isNotEqualTo(server.requests().get(1).authorization());
        }
    }

    private ApnsClient client(URI production, URI sandbox, Duration timeout) {
        return new ApnsClient(
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(),
            production,
            sandbox,
            "com.salmonbus.app",
            providerToken,
            MAPPER,
            timeout
        );
    }

    private static ClaimedDelivery delivery(PushEnvironment environment) {
        return new ClaimedDelivery(
            1L,
            UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
            2L,
            TOKEN,
            environment,
            new FavoriteTarget("234000884", "203000165", FavoriteDirection.UP),
            Instant.parse("2026-11-01T22:40:00Z"),
            0
        );
    }

    private static final class MovingClock extends Clock {
        private Instant instant;

        private MovingClock(Instant instant) {
            this.instant = instant;
        }

        private void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
