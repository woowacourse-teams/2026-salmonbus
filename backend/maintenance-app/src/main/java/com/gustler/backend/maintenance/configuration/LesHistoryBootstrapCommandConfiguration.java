package com.gustler.backend.maintenance.configuration;

import com.gustler.backend.forecasting.configuration.LesHistoryBootstrapConfiguration;
import com.gustler.backend.maintenance.DatabaseEnvironment;
import com.gustler.backend.maintenance.db.DatabaseConnections;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL 읽기 전용 연결과 체크포인트 준비 API만 구성한다. */
@Configuration(proxyBeanMethods = false)
@Import(LesHistoryBootstrapConfiguration.class)
public class LesHistoryBootstrapCommandConfiguration {
    @Bean
    DataSource dataSource(DatabaseEnvironment environment) {
        return DatabaseConnections.readOnlyDataSource(environment);
    }

    @Bean
    JdbcClient jdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }
}
