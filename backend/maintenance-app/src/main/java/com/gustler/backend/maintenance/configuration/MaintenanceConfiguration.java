package com.gustler.backend.maintenance.configuration;

import com.gustler.backend.forecasting.configuration.QualityMaintenanceConfiguration;
import com.gustler.backend.observations.configuration.CollectionInputConfiguration;
import java.time.Clock;
import com.gustler.backend.maintenance.DatabaseEnvironment;
import com.gustler.backend.maintenance.db.DatabaseConnections;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** 정비 명령에 필요한 DB 연결과 품질 기능만 구성한다. */
@Configuration(proxyBeanMethods = false)
@Import({QualityMaintenanceConfiguration.class, CollectionInputConfiguration.class})
@EnableTransactionManagement(proxyTargetClass = true)
public class MaintenanceConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    DataSource dataSource(DatabaseEnvironment environment) {
        return DatabaseConnections.dataSource(environment);
    }

    @Bean
    JdbcClient jdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
