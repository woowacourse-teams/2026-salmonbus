package com.gustler.backend.worker.station;

import com.gustler.backend.gbis.api.GbisClientOptions;
import com.gustler.backend.quota.configuration.StationQuotaConfiguration;
import com.gustler.backend.stations.api.FillStationTiles;
import com.gustler.backend.stations.api.StationFillPolicy;
import com.gustler.backend.stations.configuration.StationsConfiguration;
import com.gustler.backend.worker.configuration.GbisProperties;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 정류장 채우기 배치를 켠다. 기존 수집·예보·통계·처리 스케줄러와 따로 스레드 1개를 쓴다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "stations.fill", name = "enabled", havingValue = "true")
@EnableConfigurationProperties({ StationProperties.class, GbisProperties.class })
@Import({ StationsConfiguration.class, StationQuotaConfiguration.class })
public class StationRuntimeConfiguration {

    @Bean
    GbisClientOptions stationGbisOptions(GbisProperties keys) {
        return new GbisClientOptions(keys.baseUrl(), keys.serviceKeys());
    }

    @Bean
    StationFillPolicy stationFillPolicy(StationProperties properties) {
        return new StationFillPolicy(
            properties.keyAlias(),
            properties.dailyLimit(),
            properties.remainingFloor(),
            properties.blockWait(),
            properties.blockWaitMaximum(),
            properties.refreshInterval(),
            properties.retryBase(),
            properties.retryMaximum(),
            properties.maxFailures(),
            properties.salmonTileCheckInterval()
        );
    }

    @Bean(name = "stationFillTaskScheduler")
    ThreadPoolTaskScheduler stationFillTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("station-fill-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Bean
    StationFillScheduler stationFillScheduler(
        FillStationTiles fill,
        DataSource dataSource,
        Clock clock,
        StationProperties properties
    ) {
        return new StationFillScheduler(fill, dataSource, clock, properties.idleInterval());
    }
}
