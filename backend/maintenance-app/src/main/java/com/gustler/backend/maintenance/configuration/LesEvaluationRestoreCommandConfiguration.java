package com.gustler.backend.maintenance.configuration;

import com.gustler.backend.forecasting.configuration.LesEvaluationRestoreConfiguration;
import com.gustler.backend.maintenance.DatabaseEnvironment;
import com.gustler.backend.maintenance.db.DatabaseConnections;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@Import(LesEvaluationRestoreConfiguration.class)
public class LesEvaluationRestoreCommandConfiguration {
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
