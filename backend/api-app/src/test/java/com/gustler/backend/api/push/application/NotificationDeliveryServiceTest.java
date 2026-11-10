package com.gustler.backend.api.push.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gustler.backend.api.favorite.domain.FavoriteDirection;
import com.gustler.backend.api.favorite.domain.FavoriteTarget;
import com.gustler.backend.api.push.application.NotificationDeliveryRepository.ClaimedDelivery;
import com.gustler.backend.api.push.application.NotificationSettingRepository.DueSetting;
import com.gustler.backend.api.push.domain.DeliveryDecision;
import com.gustler.backend.api.push.domain.DeliveryOutcome;
import com.gustler.backend.api.push.domain.DeliveryPolicy;
import com.gustler.backend.api.push.domain.DeliveryStatus;
import com.gustler.backend.api.push.domain.NotificationSchedule;
import com.gustler.backend.api.push.domain.PushEnvironment;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationDeliveryServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-01T22:40:00Z");
    private static final Instant NEXT = Instant.parse("2026-11-02T22:40:00Z");
    private static final UUID SETTING_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final long MEMBER_ID = 41L;
    private static final FavoriteTarget TARGET = new FavoriteTarget("234000884", "203000165", FavoriteDirection.UP);
    private static final NotificationSchedule SCHEDULE = NotificationSchedule.parse(
        "07:40",
        List.of("MON", "TUE", "WED", "THU", "FRI")
    );
    private static final DueSetting DUE = new DueSetting(SETTING_ID, MEMBER_ID, TARGET, SCHEDULE, NOW);

    private final NotificationSettingRepository settings = mock(NotificationSettingRepository.class);
    private final NotificationDeliveryRepository deliveries = mock(NotificationDeliveryRepository.class);
    private final PushDeviceRepository devices = mock(PushDeviceRepository.class);
    private final NotificationDeliveryService service = new NotificationDeliveryService(
        settings,
        deliveries,
        devices,
        Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void expandCreatesDeliveriesAndAdvancesWhenFavoriteExists() {
        when(settings.hasFavorite(MEMBER_ID, TARGET)).thenReturn(true);
        when(settings.advance(SETTING_ID, NOW, NEXT, NOW)).thenReturn(true);

        service.expand(DUE);

        verify(deliveries).createForMemberDevices(SETTING_ID, NOW, MEMBER_ID, NOW);
        verify(settings).advance(SETTING_ID, NOW, NEXT, NOW);
        verify(settings, never()).deleteOrphan(any(), any());
    }

    @Test
    void expandCreatesNoDeliveriesWhenSettingChangedAfterItWasRead() {
        when(settings.hasFavorite(MEMBER_ID, TARGET)).thenReturn(true);
        when(settings.advance(SETTING_ID, NOW, NEXT, NOW)).thenReturn(false);

        service.expand(DUE);

        verify(deliveries, never()).createForMemberDevices(any(), any(), anyLong(), any());
    }

    @Test
    void expandDeletesOrphanWhenFavoriteIsMissing() {
        when(settings.hasFavorite(MEMBER_ID, TARGET)).thenReturn(false);

        service.expand(DUE);

        verify(settings).deleteOrphan(SETTING_ID, NOW);
        verify(deliveries, never()).createForMemberDevices(any(), any(), anyLong(), any());
        verify(settings, never()).advance(any(), any(), any(), any());
    }

    @Test
    void expandAdvancesWhenMemberHasNoDevices() {
        when(settings.hasFavorite(MEMBER_ID, TARGET)).thenReturn(true);
        when(settings.advance(SETTING_ID, NOW, NEXT, NOW)).thenReturn(true);
        when(deliveries.createForMemberDevices(SETTING_ID, NOW, MEMBER_ID, NOW)).thenReturn(0);

        service.expand(DUE);

        verify(deliveries).createForMemberDevices(SETTING_ID, NOW, MEMBER_ID, NOW);
        verify(settings).advance(SETTING_ID, NOW, NEXT, NOW);
    }

    @Test
    void findDueUsesInjectedClock() {
        service.findDue(100);
        verify(settings).findDue(NOW, 100);
    }

    @Test
    void claimDueLeasesForTheLeaseDurationAndSkipsExpiredDeliveries() {
        service.claimDue(10);
        verify(deliveries).claimDue(NOW, NOW.plus(DeliveryPolicy.LEASE), DeliveryPolicy.expiryCutoff(NOW), 10);
    }

    @Test
    void recordDeletesDeviceOnlyWhenTokenIsRejected() {
        ClaimedDelivery delivery = claimed();

        service.record(delivery, DeliveryOutcome.TOKEN_REJECTED);

        verify(devices).deleteById(delivery.deviceId());
        verify(deliveries, never()).record(anyLong(), any(), any());
    }

    @Test
    void recordStoresSentDecision() {
        ClaimedDelivery delivery = claimed();

        service.record(delivery, DeliveryOutcome.SENT);

        verify(deliveries).record(delivery.id(), new DeliveryDecision(DeliveryStatus.SENT, NOW), NOW);
        verify(devices, never()).deleteById(anyLong());
    }

    @Test
    void recordStoresRetryableDecision() {
        ClaimedDelivery delivery = claimed();

        service.record(delivery, DeliveryOutcome.RETRYABLE);

        verify(deliveries).record(
            delivery.id(),
            DeliveryPolicy.decide(DeliveryOutcome.RETRYABLE, delivery.occurrenceAt(), NOW),
            NOW
        );
    }

    @Test
    void recordStoresConfigurationErrorDecision() {
        ClaimedDelivery delivery = claimed();

        service.record(delivery, DeliveryOutcome.CONFIGURATION_ERROR);

        verify(deliveries).record(delivery.id(), new DeliveryDecision(DeliveryStatus.GAVE_UP, NOW), NOW);
    }

    private static ClaimedDelivery claimed() {
        return new ClaimedDelivery(
            9L,
            SETTING_ID,
            88L,
            "aabbcc",
            PushEnvironment.SANDBOX,
            TARGET,
            NOW,
            0
        );
    }
}
