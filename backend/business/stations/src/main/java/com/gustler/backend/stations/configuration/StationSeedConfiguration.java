package com.gustler.backend.stations.configuration;

import com.gustler.backend.stations.api.ImportStationSeed;
import com.gustler.backend.stations.infrastructure.jdbc.JdbcStationSeedImporter;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
public class StationSeedConfiguration {

    @Bean
    ImportStationSeed importStationSeed(
        JdbcClient jdbc,
        JdbcTemplate batch,
        PlatformTransactionManager manager,
        Clock clock
    ) {
        return new JdbcStationSeedImporter(jdbc, batch, manager, clock);
    }
}
