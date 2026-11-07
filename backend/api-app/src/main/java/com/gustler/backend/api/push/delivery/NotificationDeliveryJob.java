package com.gustler.backend.api.push.delivery;

import com.gustler.backend.api.push.application.NotificationDeliveryRepository.ClaimedDelivery;
import com.gustler.backend.api.push.application.NotificationDeliveryService;
import com.gustler.backend.api.push.application.NotificationSettingRepository.DueSetting;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class NotificationDeliveryJob {
    static final int EXPAND_BATCH_SIZE = 100;
    static final int SEND_BATCH_SIZE = 10;
    static final int MAX_BATCHES_PER_RUN = 20;

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationDeliveryJob.class);

    private final NotificationDeliveryService service;
    private final NotificationDeliveryRunner runner;

    public NotificationDeliveryJob(NotificationDeliveryService service, NotificationDeliveryRunner runner) {
        this.service = service;
        this.runner = runner;
    }

    @Scheduled(cron = "${push.delivery.cron:0 * * * * *}")
    public void run() {
        try {
            expandDue();
        } catch (RuntimeException exception) {
            LOGGER.error(
                "event=notification_expand status=FAILED cause={}",
                exception.getClass().getSimpleName()
            );
        }
        try {
            sendDue();
        } catch (RuntimeException exception) {
            LOGGER.error(
                "event=notification_send status=FAILED cause={}",
                exception.getClass().getSimpleName()
            );
        }
    }

    private void expandDue() {
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            List<DueSetting> due = service.findDue(EXPAND_BATCH_SIZE);
            for (DueSetting setting : due) {
                try {
                    service.expand(setting);
                } catch (RuntimeException exception) {
                    LOGGER.error(
                        "event=notification_expand status=FAILED settingId={} cause={}",
                        setting.id(),
                        exception.getClass().getSimpleName()
                    );
                }
            }
            if (due.size() < EXPAND_BATCH_SIZE) {
                return;
            }
        }
    }

    private void sendDue() {
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            List<ClaimedDelivery> claimed = service.claimDue(SEND_BATCH_SIZE);
            claimed.forEach(runner::attempt);
            if (claimed.size() < SEND_BATCH_SIZE) {
                return;
            }
        }
    }
}
