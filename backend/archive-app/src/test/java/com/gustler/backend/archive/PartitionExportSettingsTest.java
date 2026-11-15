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
    /** 한국 시각 2026-11-16 00:00. */
    static final Instant KOREAN_MIDNIGHT = Instant.parse("2026-11-15T15:00:00Z");
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

    /** 그 보관 날짜 수로 지울 수 있는 가장 늦은 파티션이 끝나는 순간(다음 날 한국 00시)이다. */
    static Instant endOfLastEligibleDate(Instant now, final int retentionDays) {
        return PartitionExportService.lastEligibleDate(Clock.fixed(now, ZoneOffset.UTC), retentionDays)
            .plusDays(1).atStartOfDay(ArchiveRetention.ZONE).toInstant();
    }

    @Test
    void 범위를_벗어난_설정값은_받지_않는다() {
        // given
        Duration hour = Duration.ofHours(1);
        Duration second = Duration.ofSeconds(1);

        // then
        for (Runnable invalid : List.<Runnable>of(
            () -> settings(3, "salmonbus/prod/lake-v3/", hour, 2000, second),
            () -> settings(31, "salmonbus/prod/lake-v3/", hour, 2000, second),
            () -> settings(4, "salmonbus/prod/lake-v3", hour, 2000, second),
            () -> settings(4, "/salmonbus/prod/lake-v3/", hour, 2000, second),
            () -> settings(4, "salmonbus-be/lake-v3/", hour, 2000, second),
            () -> settings(4, "salmonbus/prod/lake-v3/", Duration.ofSeconds(59), 2000, second),
            () -> settings(4, "salmonbus/prod/lake-v3/", Duration.ofHours(25), 2000, second),
            () -> settings(4, "salmonbus/prod/lake-v3/", hour, 0, second),
            () -> settings(4, "salmonbus/prod/lake-v3/", hour, 10001, second),
            () -> settings(4, "salmonbus/prod/lake-v3/", hour, 2000, Duration.ofMillis(99)),
            () -> settings(4, "salmonbus/prod/lake-v3/", hour, 2000, Duration.ofSeconds(11)))) {
            assertThatThrownBy(invalid::run).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void 보관_날짜_수는_다시_읽는_가장_긴_범위를_덮는_나흘부터_30일까지_받는다() {
        // given
        Duration hour = Duration.ofHours(1);
        Duration second = Duration.ofSeconds(1);

        // when
        PartitionExportSettings shortest = settings(4, "salmonbus/prod/lake-v3/", hour, 2000, second);
        PartitionExportSettings longest = settings(30, "salmonbus/prod/lake-v3/", hour, 2000, second);

        // then
        assertSoftly(softly -> {
            softly.assertThat(PartitionExportSettings.MIN_RETENTION_DAYS).isEqualTo(4);
            softly.assertThat(shortest.retentionDays()).isEqualTo(PartitionExportSettings.MIN_RETENTION_DAYS);
            softly.assertThat(longest.retentionDays()).isEqualTo(PartitionExportSettings.MAX_RETENTION_DAYS);
        });
    }

    @Test
    void 가장_작은_보관_날짜_수는_다시_읽는_범위를_날짜로_올림한_값에_하루를_더한_값이다() {
        assertSoftly(softly -> {
            softly.assertThat(PartitionExportSettings.minimumRetentionDays(Duration.ofHours(12))).isEqualTo(2);
            softly.assertThat(PartitionExportSettings.minimumRetentionDays(Duration.ofHours(24))).isEqualTo(2);
            softly.assertThat(PartitionExportSettings.minimumRetentionDays(Duration.ofHours(24).plusNanos(1)))
                .isEqualTo(3);
            softly.assertThat(PartitionExportSettings.minimumRetentionDays(Duration.ofHours(48))).isEqualTo(3);
            softly.assertThat(PartitionExportSettings.minimumRetentionDays(PartitionExportSettings.LONGEST_REPLAY))
                .isEqualTo(4);
            softly.assertThat(PartitionExportSettings.minimumRetentionDays(Duration.ofHours(72))).isEqualTo(4);
            softly.assertThat(PartitionExportSettings.minimumRetentionDays(Duration.ofHours(72).plusNanos(1)))
                .isEqualTo(5);
        });
    }

    @Test
    void 가장_작은_보관_날짜_수로_지우는_파티션의_마지막_순간은_하루_어느_때에도_다시_읽는_범위_밖이다() {
        // given
        List<Instant> throughTheDay = List.of(KOREAN_MIDNIGHT, KOREAN_MIDNIGHT.plusNanos(1),
            KOREAN_MIDNIGHT.plus(Duration.ofHours(2).plusMinutes(10)), KOREAN_MIDNIGHT.plus(Duration.ofHours(12)),
            KOREAN_MIDNIGHT.plus(Duration.ofHours(24)).minusNanos(1));

        // then
        assertSoftly(softly -> {
            for (Instant now : throughTheDay) {
                Instant newestDropped = endOfLastEligibleDate(now, PartitionExportSettings.MIN_RETENTION_DAYS);
                softly.assertThat(newestDropped).as("지금 %s", now)
                    .isBeforeOrEqualTo(now.minus(PartitionExportSettings.LONGEST_REPLAY));
            }
        });
    }

    @Test
    void 보관_날짜_수가_하루_적으면_자정부터_범위가_끝나는_시각까지는_다시_읽는_줄을_지우게_된다() {
        // given
        final int oneDayShort = PartitionExportSettings.MIN_RETENTION_DAYS - 1;
        Duration untilOutOfReplay = PartitionExportSettings.LONGEST_REPLAY.minus(Duration.ofDays(oneDayShort - 1));
        Instant rangeEnd = KOREAN_MIDNIGHT.plus(untilOutOfReplay);

        // when
        Instant atMidnight = endOfLastEligibleDate(KOREAN_MIDNIGHT, oneDayShort);
        Instant justBeforeRangeEnd = endOfLastEligibleDate(rangeEnd.minusNanos(1), oneDayShort);
        Instant atRangeEnd = endOfLastEligibleDate(rangeEnd, oneDayShort);

        // then
        assertSoftly(softly -> {
            softly.assertThat(untilOutOfReplay).isEqualTo(Duration.ofHours(2).plusMinutes(10));
            softly.assertThat(atMidnight).isAfter(KOREAN_MIDNIGHT.minus(PartitionExportSettings.LONGEST_REPLAY));
            softly.assertThat(justBeforeRangeEnd)
                .isAfter(rangeEnd.minusNanos(1).minus(PartitionExportSettings.LONGEST_REPLAY));
            softly.assertThat(atRangeEnd).isEqualTo(rangeEnd.minus(PartitionExportSettings.LONGEST_REPLAY));
        });
    }

    @Test
    void 대상_날짜는_한국_자정에_바뀐다() {
        // when
        LocalDate beforeMidnight = PartitionExportService.lastEligibleDate(
            Clock.fixed(KOREAN_MIDNIGHT.minusSeconds(1), ZoneOffset.UTC), 4);
        LocalDate atMidnight = PartitionExportService.lastEligibleDate(
            Clock.fixed(KOREAN_MIDNIGHT, ZoneOffset.UTC), 4);

        // then
        assertSoftly(softly -> {
            softly.assertThat(beforeMidnight).isEqualTo(LocalDate.parse("2026-11-11"));
            softly.assertThat(atMidnight).isEqualTo(LocalDate.parse("2026-11-12"));
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
            assertThat(settings).isEqualTo(new PartitionExportSettings(false, 4, "salmonbus/prod/lake-v3/",
                Duration.ofHours(1), 2000, Duration.ofSeconds(2)));
            assertThat(context).hasSingleBean(PartitionExportService.class);
        });
        runner.withPropertyValues("archive.partition-export.enabled=true",
            "archive.partition-export.retention-days=3").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("archive.partition-export.enabled=true",
            "archive.partition-export.prefix=salmonbus/prod/lake-v3").run(context -> assertThat(context).hasFailed());
        verifyNoInteractions(jdbc, objects);
    }
}
