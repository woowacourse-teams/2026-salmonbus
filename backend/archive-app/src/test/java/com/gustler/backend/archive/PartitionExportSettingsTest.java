package com.gustler.backend.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

class PartitionExportSettingsTest {
    final JdbcClient jdbc = mock(JdbcClient.class);
    final ArchiveObjects objects = mock(ArchiveObjects.class);
    final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withInitializer(context -> context.getBeanFactory()
            .setConversionService(ApplicationConversionService.getSharedInstance()))
        .withUserConfiguration(PartitionExportConfiguration.class)
        .withBean(JdbcClient.class, () -> jdbc)
        .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
        .withBean(ArchiveObjects.class, () -> objects)
        .withBean(Clock.class, Clock::systemUTC)
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    PartitionExportSettings settings(
        final int retentionDays,
        String prefix,
        Duration interval,
        final int pageSize,
        Duration lockTimeout
    ) {
        return new PartitionExportSettings(false, retentionDays, prefix, interval, pageSize, lockTimeout);
    }

    @Test
    void 범위를_벗어난_설정값은_받지_않는다() {
        // given
        Duration hour = Duration.ofHours(1);
        Duration second = Duration.ofSeconds(1);

        // then
        for (Runnable invalid : List.<Runnable>of(
            () -> settings(1, "salmonbus/prod/lake-v3/", hour, 2000, second),
            () -> settings(31, "salmonbus/prod/lake-v3/", hour, 2000, second),
            () -> settings(3, "salmonbus/prod/lake-v3", hour, 2000, second),
            () -> settings(3, "/salmonbus/prod/lake-v3/", hour, 2000, second),
            () -> settings(3, "salmonbus-be/lake-v3/", hour, 2000, second),
            () -> settings(3, "salmonbus/prod/lake-v3/", Duration.ofSeconds(59), 2000, second),
            () -> settings(3, "salmonbus/prod/lake-v3/", Duration.ofHours(25), 2000, second),
            () -> settings(3, "salmonbus/prod/lake-v3/", hour, 0, second),
            () -> settings(3, "salmonbus/prod/lake-v3/", hour, 10001, second),
            () -> settings(3, "salmonbus/prod/lake-v3/", hour, 2000, Duration.ofMillis(99)),
            () -> settings(3, "salmonbus/prod/lake-v3/", hour, 2000, Duration.ofSeconds(11)))) {
            assertThatThrownBy(invalid::run).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void 대상_날짜는_한국_자정에_바뀐다() {
        // when
        LocalDate beforeMidnight = PartitionExportService.lastEligibleDate(
            Clock.fixed(Instant.parse("2026-11-15T14:59:59Z"), ZoneOffset.UTC), 3);
        LocalDate atMidnight = PartitionExportService.lastEligibleDate(
            Clock.fixed(Instant.parse("2026-11-15T15:00:00Z"), ZoneOffset.UTC), 3);

        // then
        assertSoftly(softly -> {
            softly.assertThat(beforeMidnight).isEqualTo(LocalDate.parse("2026-11-12"));
            softly.assertThat(atMidnight).isEqualTo(LocalDate.parse("2026-11-13"));
        });
    }

    @Test
    void 관측_보관_날짜_수는_다시_읽는_범위를_덮는_사흘_이상이다() {
        assertSoftly(softly -> {
            softly.assertThat(PartitionExportService.observationRetentionDays(2)).isEqualTo(3);
            softly.assertThat(PartitionExportService.observationRetentionDays(3)).isEqualTo(3);
            softly.assertThat(PartitionExportService.observationRetentionDays(7)).isEqualTo(7);
        });
    }

    @Test
    void 스위치가_꺼져_있으면_내보내기_빈을_만들지_않고_DB를_보지_않는다() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(PartitionExportService.class);
            assertThat(context).doesNotHaveBean(PartitionExportRepository.class);
            assertThat(context).doesNotHaveBean(PartitionExportSettings.class);
        });
        runner.withPropertyValues("archive.partition-export.enabled=false").run(context ->
            assertThat(context).doesNotHaveBean(PartitionExportService.class));
        verifyNoInteractions(jdbc, objects);
    }

    @Test
    void 스위치가_켜지면_기본값으로_빈을_만들고_잘못된_값이면_기동하지_않는다() {
        runner.withPropertyValues("archive.partition-export.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            PartitionExportSettings settings = context.getBean(PartitionExportSettings.class);
            assertThat(settings).isEqualTo(new PartitionExportSettings(false, 3, "salmonbus/prod/lake-v3/",
                Duration.ofHours(1), 2000, Duration.ofSeconds(2)));
            assertThat(context).hasSingleBean(PartitionExportService.class);
        });
        runner.withPropertyValues("archive.partition-export.enabled=true",
            "archive.partition-export.retention-days=1").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("archive.partition-export.enabled=true",
            "archive.partition-export.prefix=salmonbus/prod/lake-v3").run(context -> assertThat(context).hasFailed());
        verifyNoInteractions(jdbc, objects);
    }
}
