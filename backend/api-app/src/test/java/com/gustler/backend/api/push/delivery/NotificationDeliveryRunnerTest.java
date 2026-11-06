package com.gustler.backend.api.push.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.gustler.backend.api.favorite.domain.FavoriteDirection;
import com.gustler.backend.api.favorite.domain.FavoriteTarget;
import com.gustler.backend.api.push.application.NotificationDeliveryRepository.ClaimedDelivery;
import com.gustler.backend.api.push.application.NotificationDeliveryService;
import com.gustler.backend.api.push.domain.DeliveryOutcome;
import com.gustler.backend.api.push.domain.PushEnvironment;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class NotificationDeliveryRunnerTest {
    private static final String TOKEN = "secretdevicetokenaabb";
    private final NotificationDeliveryService service = mock(NotificationDeliveryService.class);

    @Test
    void recordsSentAndLogsDeliveredEvent(CapturedOutput output) {
        NotificationDeliveryRunner runner = new NotificationDeliveryRunner(
            service,
            delivery -> new ApnsResponse(DeliveryOutcome.SENT, "OK")
        );
        ClaimedDelivery delivery = claimed();

        runner.attempt(delivery);

        verify(service).record(delivery, DeliveryOutcome.SENT);
        assertThat(output).contains("event=notification_delivered");
        assertThat(output.getOut() + output.getErr()).doesNotContain(TOKEN);
    }

    @Test
    void recordsRetryableWhenSendThrowsAndHidesToken(CapturedOutput output) {
        NotificationDeliveryRunner runner = new NotificationDeliveryRunner(
            service,
            delivery -> {
                throw new RuntimeException("token=" + TOKEN);
            }
        );
        ClaimedDelivery delivery = claimed();

        runner.attempt(delivery);

        verify(service).record(delivery, DeliveryOutcome.RETRYABLE);
        assertThat(output).contains("status=RETRY");
        assertThat(output.getOut() + output.getErr()).doesNotContain(TOKEN);
    }

    @Test
    void swallowsRecordFailure(CapturedOutput output) {
        doThrow(new RuntimeException("record-failed")).when(service).record(any(), any());
        NotificationDeliveryRunner runner = new NotificationDeliveryRunner(
            service,
            delivery -> new ApnsResponse(DeliveryOutcome.SENT, "OK")
        );

        assertThatCode(() -> runner.attempt(claimed())).doesNotThrowAnyException();
        assertThat(output).contains("event=notification_delivery_record");
        assertThat(output.getOut() + output.getErr()).doesNotContain(TOKEN);
    }

    @Test
    void logsConfigurationErrorAtErrorLevel(CapturedOutput output) {
        NotificationDeliveryRunner runner = new NotificationDeliveryRunner(
            service,
            delivery -> new ApnsResponse(DeliveryOutcome.CONFIGURATION_ERROR, "InvalidProviderToken")
        );

        runner.attempt(claimed());

        verify(service).record(claimed(), DeliveryOutcome.CONFIGURATION_ERROR);
        assertThat(output).contains("status=CONFIG_ERROR");
        assertThat(output.getOut() + output.getErr()).doesNotContain(TOKEN);
    }

    @Test
    void claimedDeliveryToStringHidesToken() {
        assertThat(claimed().toString()).isEqualTo("ClaimedDelivery[id=7]");
        assertThat(claimed().toString()).doesNotContain(TOKEN);
    }

    private static ClaimedDelivery claimed() {
        return new ClaimedDelivery(
            7L,
            UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
            3L,
            TOKEN,
            PushEnvironment.SANDBOX,
            new FavoriteTarget("234000884", "203000165", FavoriteDirection.UP),
            Instant.parse("2026-11-01T22:40:00Z"),
            0
        );
    }
}
