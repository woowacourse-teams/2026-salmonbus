package com.gustler.backend.api.push.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gustler.backend.api.push.application.NotificationDeliveryRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class NotificationDeliveryPurgeTest {
    private static final Instant NOW = Instant.parse("2026-11-09T04:41:00Z");
    private static final Duration RETENTION = Duration.ofDays(7);
    private static final Instant CUTOFF = NOW.minus(RETENTION);

    private final NotificationDeliveryRepository deliveries = mock(NotificationDeliveryRepository.class);
    private final Clock clock = spy(Clock.fixed(NOW, ZoneOffset.UTC));
    private final NotificationDeliveryPurge purge = new NotificationDeliveryPurge(deliveries, clock, RETENTION);

    @Test
    void deletesFullBatchesUntilOneIsShortAndLogsTotal(CapturedOutput output) {
        when(deliveries.deleteCompletedBefore(CUTOFF, 1_000)).thenReturn(1_000, 1_000, 7);

        purge.purge();

        verify(deliveries, times(3)).deleteCompletedBefore(CUTOFF, 1_000);
        verify(clock).instant();
        assertThat(output).contains("event=notification_delivery_purge deleted=2007");
    }

    @Test
    void stopsAfterOneEmptyBatchAndLogsZero(CapturedOutput output) {
        when(deliveries.deleteCompletedBefore(CUTOFF, 1_000)).thenReturn(0);

        purge.purge();

        verify(deliveries).deleteCompletedBefore(CUTOFF, 1_000);
        assertThat(output).contains("event=notification_delivery_purge deleted=0");
    }

    @Test
    void checksForMoreRowsAfterAnExactlyFullBatch(CapturedOutput output) {
        when(deliveries.deleteCompletedBefore(CUTOFF, 1_000)).thenReturn(1_000, 0);

        purge.purge();

        verify(deliveries, times(2)).deleteCompletedBefore(CUTOFF, 1_000);
        assertThat(output).contains("event=notification_delivery_purge deleted=1000");
    }

    @Test
    void logsFailureWithoutThrowingAndCanRunAgain(CapturedOutput output) {
        when(deliveries.deleteCompletedBefore(CUTOFF, 1_000))
            .thenThrow(new IllegalStateException("private-database-details"))
            .thenReturn(3);

        assertThatCode(purge::purge).doesNotThrowAnyException();
        purge.purge();

        verify(deliveries, times(2)).deleteCompletedBefore(CUTOFF, 1_000);
        assertThat(output).contains(
            "ERROR",
            "event=notification_delivery_purge deleted=0 status=FAILED cause=IllegalStateException",
            "event=notification_delivery_purge deleted=3"
        ).doesNotContain("private-database-details");
    }

    @Test
    void failureLogIncludesRowsDeletedInEarlierBatches(CapturedOutput output) {
        when(deliveries.deleteCompletedBefore(CUTOFF, 1_000))
            .thenReturn(1_000)
            .thenThrow(new IllegalStateException("private-database-details"));

        assertThatCode(purge::purge).doesNotThrowAnyException();

        verify(deliveries, times(2)).deleteCompletedBefore(CUTOFF, 1_000);
        assertThat(output).contains(
            "ERROR",
            "event=notification_delivery_purge deleted=1000 status=FAILED cause=IllegalStateException"
        ).doesNotContain("private-database-details");
    }
}
