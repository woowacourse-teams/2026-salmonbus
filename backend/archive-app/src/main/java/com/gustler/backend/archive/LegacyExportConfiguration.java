package com.gustler.backend.archive;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.unit.DataSize;

/**
 * 옛 테이블 내보내기의 빈을 만든다. {@code archive.legacy-export.enabled=true} 일 때만 적용되며, 꺼져 있으면 저장소·서비스·예약
 * 작업이 하나도 생기지 않아 DB 를 보지 않는다. 설정값이 범위를 벗어나면 {@link LegacyExportSettings} 가 기동을 막는다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "archive.legacy-export", name = "enabled", havingValue = "true")
public class LegacyExportConfiguration {
    @Bean
    LegacyExportSettings legacyExportSettings(
        @Value("${archive.legacy-export.prefix:salmonbus/prod/lake-v3-legacy/}") String prefix,
        @Value("${archive.legacy-export.interval:1m}") Duration interval,
        @Value("${archive.legacy-export.page-size:2000}") final int pageSize,
        @Value("${archive.legacy-export.page-pause:200ms}") Duration pagePause,
        @Value("${archive.legacy-export.segment-size:64MB}") DataSize segmentSize,
        @Value("${archive.legacy-export.max-rows-per-run:2000000}") final long maxRowsPerRun,
        @Value("${archive.legacy-export.max-run-duration:10m}") Duration maxRunDuration,
        @Value("${archive.legacy-export.statement-timeout:60s}") Duration statementTimeout,
        @Value("${archive.partition-export.prefix:salmonbus/prod/lake-v3/}") String partitionPrefix
    ) {
        return new LegacyExportSettings(prefix, interval, pageSize, pagePause, segmentSize.toBytes(), maxRowsPerRun,
            maxRunDuration, statementTimeout, partitionPrefix);
    }

    @Bean
    LegacyExportRepository legacyExportRepository(
        JdbcClient jdbc,
        PlatformTransactionManager manager,
        LegacyExportSettings settings
    ) {
        return new LegacyExportRepository(jdbc, manager, settings.statementTimeout());
    }

    @Bean
    LegacyExportService legacyExportService(
        LegacyExportRepository repository,
        ArchiveObjects objects,
        Clock clock,
        MeterRegistry metrics,
        LegacyExportSettings settings
    ) {
        return new LegacyExportService(repository, objects, clock, metrics, settings,
            LegacyExportService.Pause.THREAD_SLEEP);
    }
}
