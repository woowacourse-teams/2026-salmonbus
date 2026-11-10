package com.gustler.backend.api.push.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.gustler.backend.api.push.application.NotificationDeliveryRepository;
import com.gustler.backend.api.push.application.NotificationDeliveryService;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.CronTask;
import tools.jackson.databind.ObjectMapper;

class PushDeliveryConfigurationTest {
    private static final Instant NOW = Instant.parse("2026-11-09T04:41:00Z");

    @TempDir
    Path directory;

    private final NotificationDeliveryRepository deliveries = Mockito.mock(NotificationDeliveryRepository.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(PushDeliveryConfiguration.class)
        .withBean(NotificationDeliveryService.class, () -> Mockito.mock(NotificationDeliveryService.class))
        .withBean(NotificationDeliveryRepository.class, () -> deliveries)
        .withBean(Clock.class, () -> Clock.fixed(NOW, ZoneOffset.UTC))
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withPropertyValues("push.delivery.cron=-")
        .withInitializer(context -> context.getBeanFactory()
            .setConversionService(ApplicationConversionService.getSharedInstance()));

    @Test
    void defaultDoesNotRegisterDeliveryBeans() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(NotificationDeliveryJob.class);
            assertThat(context).doesNotHaveBean(NotificationDeliveryPurge.class);
            assertThat(context).doesNotHaveBean(NotificationDeliveryRunner.class);
            assertThat(context).doesNotHaveBean(ApnsClient.class);
            assertThat(context).doesNotHaveBean(ApnsProviderToken.class);
        });
    }

    @Test
    void disabledDoesNotRegisterPurgeOrScheduler() {
        runner.withPropertyValues("push.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(NotificationDeliveryPurge.class);
            assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
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
        enabledRunner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(NotificationDeliveryJob.class);
            assertThat(context).hasSingleBean(NotificationDeliveryPurge.class);
            assertThat(context).hasSingleBean(NotificationDeliveryRunner.class);
            assertThat(context).hasSingleBean(ApnsClient.class);
            assertThat(context).hasSingleBean(ApnsProviderToken.class);
            assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks())
                .singleElement().satisfies(task -> {
                    assertThat(task.getTask().toString()).endsWith(NotificationDeliveryPurge.class.getName() + ".purge");
                    assertThat(task.getTask()).isInstanceOfSatisfying(CronTask.class, cron ->
                        assertThat(cron.getExpression()).isEqualTo("0 41 4 * * *"));
                });

            context.getBean(NotificationDeliveryPurge.class).purge();

            verify(deliveries).deleteCompletedBefore(NOW.minus(Duration.ofDays(7)), 1_000);
        });
    }

    @Test
    void purgeUsesConfiguredRetentionAndCron() throws Exception {
        enabledRunner().withPropertyValues(
            "push.delivery.retention=2d",
            "push.delivery.purge-cron=0 52 3 * * *"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks())
                .singleElement().satisfies(task ->
                    assertThat(task.getTask()).isInstanceOfSatisfying(CronTask.class, cron ->
                        assertThat(cron.getExpression()).isEqualTo("0 52 3 * * *")));

            context.getBean(NotificationDeliveryPurge.class).purge();

            verify(deliveries).deleteCompletedBefore(NOW.minus(Duration.ofDays(2)), 1_000);
        });
    }

    @Test
    void deliveryRunsOnItsOwnThreadWhileAnotherScheduledJobIsStuck() throws Exception {
        Path keyFile = TestApnsKeys.writePkcs8Pem(
            TestApnsKeys.ecKeyPair("secp256r1"),
            directory.resolve("AuthKey.p8")
        );
        NotificationDeliveryService service = Mockito.mock(NotificationDeliveryService.class);
        AtomicReference<String> deliveryThread = new AtomicReference<>();
        CountDownLatch delivered = new CountDownLatch(1);
        Mockito.when(service.findDue(Mockito.anyInt())).thenAnswer(invocation -> {
            deliveryThread.compareAndSet(null, Thread.currentThread().getName());
            delivered.countDown();
            return List.of();
        });
        new ApplicationContextRunner()
            .withUserConfiguration(PushDeliveryConfiguration.class, StuckScheduledJob.class)
            .withBean(NotificationDeliveryService.class, () -> service)
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(NotificationDeliveryRepository.class, () -> deliveries)
            .withInitializer(context -> context.getBeanFactory()
                .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withPropertyValues(
                "push.enabled=true",
                "push.delivery.cron=* * * * * *",
                "push.apns.team-id=TEAMTEST01",
                "push.apns.key-id=KEYTEST001",
                "push.apns.private-key-path=" + keyFile,
                "push.apns.topic=com.salmonbus.app",
                "push.apns.production-uri=http://127.0.0.1:9",
                "push.apns.sandbox-uri=http://127.0.0.1:9"
            ).run(context -> {
                try {
                    assertThat(StuckScheduledJob.started.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(deliveryThread.get()).startsWith("push-delivery-");
                } finally {
                    StuckScheduledJob.release.countDown();
                }
            });
    }

    @Test
    void dashCronTurnsTheDeliveryScheduleOff() throws Exception {
        Path keyFile = TestApnsKeys.writePkcs8Pem(
            TestApnsKeys.ecKeyPair("secp256r1"),
            directory.resolve("AuthKey.p8")
        );
        NotificationDeliveryService service = Mockito.mock(NotificationDeliveryService.class);
        new ApplicationContextRunner()
            .withUserConfiguration(PushDeliveryConfiguration.class)
            .withBean(NotificationDeliveryService.class, () -> service)
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(NotificationDeliveryRepository.class, () -> deliveries)
            .withInitializer(context -> context.getBeanFactory()
                .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withPropertyValues(
            "push.enabled=true",
            "push.delivery.cron=-",
            "push.apns.team-id=TEAMTEST01",
            "push.apns.key-id=KEYTEST001",
            "push.apns.private-key-path=" + keyFile,
            "push.apns.topic=com.salmonbus.app",
            "push.apns.production-uri=http://127.0.0.1:9",
            "push.apns.sandbox-uri=http://127.0.0.1:9"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            Thread.sleep(1_500);
            Mockito.verifyNoInteractions(service);
        });
    }

    @Test
    void productionHttpClientUsesHttp2() {
        assertThat(PushDeliveryConfiguration.apnsHttpClient().version()).isEqualTo(HttpClient.Version.HTTP_2);
    }

    private ApplicationContextRunner enabledRunner() throws Exception {
        Path keyFile = TestApnsKeys.writePkcs8Pem(
            TestApnsKeys.ecKeyPair("secp256r1"),
            directory.resolve("AuthKey.p8")
        );
        return runner.withPropertyValues(
            "push.enabled=true",
            "push.apns.team-id=TEAMTEST01",
            "push.apns.key-id=KEYTEST001",
            "push.apns.private-key-path=" + keyFile,
            "push.apns.topic=com.salmonbus.app",
            "push.apns.production-uri=http://127.0.0.1:9",
            "push.apns.sandbox-uri=http://127.0.0.1:9"
        );
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class StuckScheduledJob {
        static final CountDownLatch started = new CountDownLatch(1);
        static final CountDownLatch release = new CountDownLatch(1);

        @Scheduled(fixedDelay = 60_000)
        void holdTheDefaultSchedulerThread() throws InterruptedException {
            started.countDown();
            release.await(30, TimeUnit.SECONDS);
        }
    }

    private static String causeMessages(Throwable failure) {
        return Stream.iterate(failure, Objects::nonNull, Throwable::getCause)
            .map(Throwable::getMessage)
            .filter(Objects::nonNull)
            .collect(Collectors.joining("\n"));
    }
}
