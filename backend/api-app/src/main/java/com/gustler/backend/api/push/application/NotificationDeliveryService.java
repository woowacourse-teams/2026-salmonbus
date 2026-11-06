package com.gustler.backend.api.push.application;

import com.gustler.backend.api.push.application.NotificationDeliveryRepository.ClaimedDelivery;
import com.gustler.backend.api.push.application.NotificationSettingRepository.DueSetting;
import com.gustler.backend.api.push.domain.DeliveryOutcome;
import com.gustler.backend.api.push.domain.DeliveryPolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationDeliveryService {
    private final NotificationSettingRepository settings;
    private final NotificationDeliveryRepository deliveries;
    private final PushDeviceRepository devices;
    private final Clock clock;

    public NotificationDeliveryService(
        NotificationSettingRepository settings,
        NotificationDeliveryRepository deliveries,
        PushDeviceRepository devices,
        Clock clock
    ) {
        this.settings = settings;
        this.deliveries = deliveries;
        this.devices = devices;
        this.clock = clock;
    }

    public List<DueSetting> findDue(int limit) {
        return settings.findDue(now(), limit);
    }

    @Transactional
    public void expand(DueSetting due) {
        Instant now = now();
        if (!settings.hasFavorite(due.memberId(), due.target())) {
            settings.deleteOrphan(due.id(), due.occurrenceAt());
            return;
        }
        deliveries.createForMemberDevices(due.id(), due.occurrenceAt(), due.memberId(), now);
        settings.advance(due.id(), due.occurrenceAt(), due.schedule().nextAfter(now), now);
    }

    public List<ClaimedDelivery> claimDue(int limit) {
        Instant now = now();
        return deliveries.claimDue(now, now.plus(DeliveryPolicy.LEASE), limit);
    }

    public void record(ClaimedDelivery delivery, DeliveryOutcome outcome) {
        Instant now = now();
        if (outcome == DeliveryOutcome.TOKEN_REJECTED) {
            devices.deleteById(delivery.deviceId());
            return;
        }
        deliveries.record(delivery.id(), DeliveryPolicy.decide(outcome, delivery.occurrenceAt(), now), now);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
