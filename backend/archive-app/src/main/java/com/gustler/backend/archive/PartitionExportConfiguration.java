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

/**
 * 날짜 파티션 내보내기의 빈을 만든다. {@code archive.partition-export.enabled=true} 일 때만 적용되며, 꺼져 있으면 저장소·서비스·
 * 예약 작업이 하나도 생기지 않아 카탈로그 조회도 하지 않는다. 설정값이 범위를 벗어나면 {@link PartitionExportSettings} 가 기동을 막는다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "archive.partition-export", name = "enabled", havingValue = "true")
public class PartitionExportConfiguration {
    @Bean
    PartitionExportSettings partitionExportSettings(
        @Value("${archive.partition-export.drop-enabled:false}") final boolean dropEnabled,
        @Value("${archive.partition-export.retention-days:4}") final int retentionDays,
        @Value("${archive.partition-export.prefix:salmonbus/prod/lake-v3/}") String prefix,
        @Value("${archive.partition-export.interval:1h}") Duration interval,
        @Value("${archive.partition-export.page-size:2000}") final int pageSize,
        @Value("${archive.partition-export.lock-timeout:2s}") Duration lockTimeout
    ) {
        return new PartitionExportSettings(dropEnabled, retentionDays, prefix, interval, pageSize, lockTimeout);
    }

    @Bean
    PartitionExportRepository partitionExportRepository(
        JdbcClient jdbc,
        PlatformTransactionManager manager,
        PartitionExportSettings settings
    ) {
        return new PartitionExportRepository(jdbc, manager, settings.lockTimeout());
    }

    @Bean
    PartitionExportService partitionExportService(
        PartitionExportRepository repository,
        ArchiveObjects objects,
        Clock clock,
        MeterRegistry metrics,
        PartitionExportSettings settings
    ) {
        return new PartitionExportService(repository, objects, clock, metrics, settings);
    }
}
