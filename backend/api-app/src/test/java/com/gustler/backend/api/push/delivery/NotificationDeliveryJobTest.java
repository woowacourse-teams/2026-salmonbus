package com.gustler.backend.api.push.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gustler.backend.api.favorite.domain.FavoriteDirection;
import com.gustler.backend.api.favorite.domain.FavoriteTarget;
import com.gustler.backend.api.push.application.NotificationDeliveryRepository.ClaimedDelivery;
import com.gustler.backend.api.push.application.NotificationDeliveryService;
import com.gustler.backend.api.push.application.NotificationSettingRepository.DueSetting;
import com.gustler.backend.api.push.domain.DeliveryPolicy;
import com.gustler.backend.api.push.domain.NotificationSchedule;
import com.gustler.backend.api.push.domain.PushEnvironment;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class NotificationDeliveryJobTest {
    private final NotificationDeliveryService service = mock(NotificationDeliveryService.class);
    private final NotificationDeliveryRunner runner = mock(NotificationDeliveryRunner.class);
    private final NotificationDeliveryJob job = new NotificationDeliveryJob(service, runner);

    @Test
    void stopsExpandAndSendWhenBatchesAreShort() {
        when(service.findDue(NotificationDeliveryJob.EXPAND_BATCH_SIZE)).thenReturn(List.of(due(1)));
        when(service.claimDue(NotificationDeliveryJob.SEND_BATCH_SIZE)).thenReturn(List.of(claimed(1)));

        job.run();

        verify(service, times(1)).findDue(NotificationDeliveryJob.EXPAND_BATCH_SIZE);
        verify(service).expand(any());
        verify(service, times(1)).claimDue(NotificationDeliveryJob.SEND_BATCH_SIZE);
        verify(runner, times(1)).attempt(any());
    }

    @Test
    void capsBatchesPerRun() {
        when(service.findDue(NotificationDeliveryJob.EXPAND_BATCH_SIZE))
            .thenReturn(dues(NotificationDeliveryJob.EXPAND_BATCH_SIZE));
        when(service.claimDue(NotificationDeliveryJob.SEND_BATCH_SIZE))
            .thenReturn(claimedList(NotificationDeliveryJob.SEND_BATCH_SIZE));

        job.run();

        verify(service, times(NotificationDeliveryJob.MAX_BATCHES_PER_RUN))
            .findDue(NotificationDeliveryJob.EXPAND_BATCH_SIZE);
        verify(service, times(NotificationDeliveryJob.MAX_BATCHES_PER_RUN))
            .claimDue(NotificationDeliveryJob.SEND_BATCH_SIZE);
    }

    @Test
    void continuesExpandingAfterOneSettingFails() {
        DueSetting first = due(1);
        DueSetting second = due(2);
        when(service.findDue(NotificationDeliveryJob.EXPAND_BATCH_SIZE)).thenReturn(List.of(first, second));
        when(service.claimDue(NotificationDeliveryJob.SEND_BATCH_SIZE)).thenReturn(Collections.emptyList());
        doThrow(new RuntimeException("expand-failed")).when(service).expand(first);

        job.run();

        verify(service).expand(first);
        verify(service).expand(second);
        verify(service).claimDue(NotificationDeliveryJob.SEND_BATCH_SIZE);
    }

    @Test
    void sendsAfterExpandPhaseFails() {
        when(service.findDue(NotificationDeliveryJob.EXPAND_BATCH_SIZE))
            .thenThrow(new RuntimeException("findDue-failed"));
        when(service.claimDue(NotificationDeliveryJob.SEND_BATCH_SIZE)).thenReturn(Collections.emptyList());

        job.run();

        verify(service).claimDue(NotificationDeliveryJob.SEND_BATCH_SIZE);
    }

    @Test
    void sendBatchFitsInsideLease() {
        assertThat(Duration.ofSeconds(NotificationDeliveryJob.SEND_BATCH_SIZE * 8L))
            .isLessThan(DeliveryPolicy.LEASE);
    }

    private static List<DueSetting> dues(int size) {
        return IntStream.range(0, size).mapToObj(NotificationDeliveryJobTest::due).toList();
    }

    private static List<ClaimedDelivery> claimedList(int size) {
        return IntStream.range(0, size).mapToObj(NotificationDeliveryJobTest::claimed).toList();
    }

    private static DueSetting due(int index) {
        return new DueSetting(
            UUID.fromString(String.format("00000000-0000-0000-0000-%012d", index)),
            index,
            new FavoriteTarget("234000884", "203000165", FavoriteDirection.UP),
            NotificationSchedule.parse("07:40", List.of("MON", "TUE", "WED", "THU", "FRI")),
            Instant.parse("2026-11-01T22:40:00Z")
        );
    }

    private static ClaimedDelivery claimed(int index) {
        return new ClaimedDelivery(
            index,
            UUID.fromString(String.format("00000000-0000-0000-0000-%012d", index)),
            index,
            "aa",
            PushEnvironment.SANDBOX,
            new FavoriteTarget("234000884", "203000165", FavoriteDirection.UP),
            Instant.parse("2026-11-01T22:40:00Z"),
            0
        );
    }
}
