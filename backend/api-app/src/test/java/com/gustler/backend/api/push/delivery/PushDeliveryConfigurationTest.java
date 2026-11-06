package com.gustler.backend.api.push.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.push.application.NotificationDeliveryService;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

class PushDeliveryConfigurationTest {
    @TempDir
    Path directory;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(PushDeliveryConfiguration.class)
        .withBean(NotificationDeliveryService.class, () -> Mockito.mock(NotificationDeliveryService.class))
        .withBean(Clock.class, Clock::systemUTC)
        .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void defaultDoesNotRegisterDeliveryBeans() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(NotificationDeliveryJob.class);
            assertThat(context).doesNotHaveBean(NotificationDeliveryRunner.class);
            assertThat(context).doesNotHaveBean(ApnsClient.class);
            assertThat(context).doesNotHaveBean(ApnsProviderToken.class);
        });
    }

    @Test
    void enabledWithoutValuesFailsWithEnvironmentVariableNames() {
        runner.withPropertyValues("push.enabled=true").run(context -> {
            assertThat(context).hasFailed();
            String messages = causeMessages(context.getStartupFailure());
            assertThat(messages).contains("APNS_TEAM_ID", "APNS_KEY_ID", "APNS_PRIVATE_KEY_PATH", "APNS_TOPIC");
        });
    }

    @Test
    void enabledWithValuesRegistersDeliveryBeans() throws Exception {
        Path keyFile = TestApnsKeys.writePkcs8Pem(
            TestApnsKeys.ecKeyPair("secp256r1"),
            directory.resolve("AuthKey.p8")
        );
        runner.withPropertyValues(
            "push.enabled=true",
            "push.apns.team-id=TEAMTEST01",
            "push.apns.key-id=KEYTEST001",
            "push.apns.private-key-path=" + keyFile,
            "push.apns.topic=com.salmonbus.app",
            "push.apns.production-uri=http://127.0.0.1:9",
            "push.apns.sandbox-uri=http://127.0.0.1:9"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(NotificationDeliveryJob.class);
            assertThat(context).hasSingleBean(NotificationDeliveryRunner.class);
            assertThat(context).hasSingleBean(ApnsClient.class);
            assertThat(context).hasSingleBean(ApnsProviderToken.class);
        });
    }

    @Test
    void productionHttpClientUsesHttp2() {
        assertThat(PushDeliveryConfiguration.apnsHttpClient().version()).isEqualTo(HttpClient.Version.HTTP_2);
    }

    private static String causeMessages(Throwable failure) {
        return Stream.iterate(failure, Objects::nonNull, Throwable::getCause)
            .map(Throwable::getMessage)
            .filter(Objects::nonNull)
            .collect(Collectors.joining("\n"));
    }
}
