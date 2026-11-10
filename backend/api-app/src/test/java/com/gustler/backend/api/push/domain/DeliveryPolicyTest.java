package com.gustler.backend.api.push.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DeliveryPolicyTest {
    private static final Instant OCCURRENCE = Instant.parse("2026-11-01T22:40:00Z");

    @Test
    void retryAt074000300IsPendingAt074100() {
        DeliveryDecision decision = DeliveryPolicy.decide(
            DeliveryOutcome.RETRYABLE,
            OCCURRENCE,
            Instant.parse("2026-11-01T22:40:00.300Z")
        );
        assertThat(decision.status()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(decision.nextAttemptAt()).isEqualTo(Instant.parse("2026-11-01T22:41:00Z"));
    }

    @Test
    void retryAt074100200IsPendingAt074200() {
        DeliveryDecision decision = DeliveryPolicy.decide(
            DeliveryOutcome.RETRYABLE,
            OCCURRENCE,
            Instant.parse("2026-11-01T22:41:00.200Z")
        );
        assertThat(decision.status()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(decision.nextAttemptAt()).isEqualTo(Instant.parse("2026-11-01T22:42:00Z"));
    }

    @Test
    void retryAt074159900IsPendingAt074200() {
        DeliveryDecision decision = DeliveryPolicy.decide(
            DeliveryOutcome.RETRYABLE,
            OCCURRENCE,
            Instant.parse("2026-11-01T22:41:59.900Z")
        );
        assertThat(decision.status()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(decision.nextAttemptAt()).isEqualTo(Instant.parse("2026-11-01T22:42:00Z"));
    }

    @Test
    void retryAt074200000IsGaveUp() {
        Instant now = Instant.parse("2026-11-01T22:42:00Z");
        DeliveryDecision decision = DeliveryPolicy.decide(DeliveryOutcome.RETRYABLE, OCCURRENCE, now);
        assertThat(decision.status()).isEqualTo(DeliveryStatus.GAVE_UP);
        assertThat(decision.nextAttemptAt()).isEqualTo(now);
    }

    @Test
    void retryAt074200200IsGaveUp() {
        Instant now = Instant.parse("2026-11-01T22:42:00.200Z");
        DeliveryDecision decision = DeliveryPolicy.decide(DeliveryOutcome.RETRYABLE, OCCURRENCE, now);
        assertThat(decision.status()).isEqualTo(DeliveryStatus.GAVE_UP);
        assertThat(decision.nextAttemptAt()).isEqualTo(now);
    }

    @Test
    void sentKeepsSentStatusAtNow() {
        Instant now = Instant.parse("2026-11-01T22:40:00.300Z");
        DeliveryDecision decision = DeliveryPolicy.decide(DeliveryOutcome.SENT, OCCURRENCE, now);
        assertThat(decision.status()).isEqualTo(DeliveryStatus.SENT);
        assertThat(decision.nextAttemptAt()).isEqualTo(now);
    }

    @Test
    void configurationErrorGivesUpAtNow() {
        Instant now = Instant.parse("2026-11-01T22:40:00.300Z");
        DeliveryDecision decision = DeliveryPolicy.decide(DeliveryOutcome.CONFIGURATION_ERROR, OCCURRENCE, now);
        assertThat(decision.status()).isEqualTo(DeliveryStatus.GAVE_UP);
        assertThat(decision.nextAttemptAt()).isEqualTo(now);
    }

    @Test
    void tokenRejectedHasNoNextStatus() {
        assertThatThrownBy(() -> DeliveryPolicy.decide(
            DeliveryOutcome.TOKEN_REJECTED,
            OCCURRENCE,
            Instant.parse("2026-11-01T22:40:00Z")
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constantsMatchTicketWindow() {
        assertThat(DeliveryPolicy.LEASE).isEqualTo(Duration.ofMinutes(1));
        assertThat(DeliveryPolicy.RETRY_INTERVAL).isEqualTo(Duration.ofMinutes(1));
        assertThat(DeliveryPolicy.RETRY_WINDOW).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void leaseEndsInsideRetryWindowSoACrashedSendCanBeClaimedAgain() {
        assertThat(DeliveryPolicy.LEASE).isLessThan(DeliveryPolicy.RETRY_WINDOW);
    }
}
