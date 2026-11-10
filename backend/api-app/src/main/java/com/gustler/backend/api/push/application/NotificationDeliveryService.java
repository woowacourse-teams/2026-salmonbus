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
        if (settings.advance(due.id(), due.occurrenceAt(), due.schedule().nextAfter(now), now)) {
            deliveries.createForMemberDevices(due.id(), due.occurrenceAt(), due.memberId(), now);
        }
    }

    public int giveUpExpired() {
        Instant now = now();
        return deliveries.giveUpOccurredBefore(DeliveryPolicy.expiryCutoff(now), now);
    }

    public List<ClaimedDelivery> claimDue(int limit) {
        Instant now = now();
        return deliveries.claimDue(now, now.plus(DeliveryPolicy.LEASE), DeliveryPolicy.expiryCutoff(now), limit);
    }

    // 한 묶음을 보내는 사이에도 시간이 지나므로, 가져온 건도 보내기 직전에 기한을 다시 본다.
    public boolean giveUpIfExpired(ClaimedDelivery delivery) {
        Instant now = now();
        if (!DeliveryPolicy.expired(delivery.occurrenceAt(), now)) {
            return false;
        }
        deliveries.giveUp(delivery.id(), now);
        return true;
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
