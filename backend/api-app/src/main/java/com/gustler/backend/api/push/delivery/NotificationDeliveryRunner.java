package com.gustler.backend.api.push.delivery;

import com.gustler.backend.api.push.application.NotificationDeliveryRepository.ClaimedDelivery;
import com.gustler.backend.api.push.application.NotificationDeliveryService;
import com.gustler.backend.api.push.domain.DeliveryOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class NotificationDeliveryRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationDeliveryRunner.class);

    private final NotificationDeliveryService service;
    private final ApnsSender sender;

    public NotificationDeliveryRunner(NotificationDeliveryService service, ApnsSender sender) {
        this.service = service;
        this.sender = sender;
    }

    public void attempt(ClaimedDelivery delivery) {
        if (service.giveUpIfExpired(delivery)) {
            LOGGER.warn("event=notification_delivery status=EXPIRED deliveryId={}", delivery.id());
            return;
        }
        ApnsResponse response;
        try {
            response = sender.send(delivery);
        } catch (RuntimeException exception) {
            response = new ApnsResponse(DeliveryOutcome.RETRYABLE, exception.getClass().getSimpleName());
        }
        log(delivery, response);
        try {
            service.record(delivery, response.outcome());
        } catch (RuntimeException exception) {
            LOGGER.error(
                "event=notification_delivery_record status=FAILED deliveryId={} cause={}",
                delivery.id(),
                exception.getClass().getSimpleName()
            );
        }
    }

    private static void log(ClaimedDelivery delivery, ApnsResponse response) {
        switch (response.outcome()) {
            case SENT -> LOGGER.info(
                "event=notification_delivered deliveryId={} settingId={} deviceId={} environment={}",
                delivery.id(),
                delivery.settingId(),
                delivery.deviceId(),
                delivery.environment()
            );
            case TOKEN_REJECTED -> LOGGER.info(
                "event=notification_delivery status=TOKEN_REJECTED deliveryId={} deviceId={} reason={}",
                delivery.id(),
                delivery.deviceId(),
                response.reason()
            );
            case RETRYABLE -> LOGGER.warn(
                "event=notification_delivery status=RETRY deliveryId={} deviceId={} reason={}",
                delivery.id(),
                delivery.deviceId(),
                response.reason()
            );
            case CONFIGURATION_ERROR -> LOGGER.error(
                "event=notification_delivery status=CONFIG_ERROR deliveryId={} deviceId={} reason={}",
                delivery.id(),
                delivery.deviceId(),
                response.reason()
            );
        }
    }
}
