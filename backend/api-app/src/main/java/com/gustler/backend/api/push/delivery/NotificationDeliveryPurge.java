package com.gustler.backend.api.push.delivery;

import com.gustler.backend.api.push.application.NotificationDeliveryRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class NotificationDeliveryPurge {
    static final String DEFAULT_CRON = "0 41 4 * * *";
    static final int BATCH_SIZE = 1_000;

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationDeliveryPurge.class);

    private final NotificationDeliveryRepository deliveries;
    private final Clock clock;
    private final Duration retention;

    public NotificationDeliveryPurge(NotificationDeliveryRepository deliveries, Clock clock, Duration retention) {
        this.deliveries = deliveries;
        this.clock = clock;
        this.retention = retention;
    }

    @Scheduled(cron = "${push.delivery.purge-cron:" + DEFAULT_CRON + "}", zone = "Asia/Seoul")
    public void purge() {
        long total = 0;
        try {
            Instant cutoff = clock.instant().minus(retention);
            int deleted;
            do {
                deleted = deliveries.deleteCompletedBefore(cutoff, BATCH_SIZE);
                total += deleted;
            } while (deleted == BATCH_SIZE);
            LOGGER.info("event=notification_delivery_purge deleted={}", total);
        } catch (RuntimeException exception) {
            LOGGER.error(
                "event=notification_delivery_purge deleted={} status=FAILED cause={}",
                total,
                exception.getClass().getSimpleName()
            );
        }
    }
}
