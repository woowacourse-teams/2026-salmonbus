package com.gustler.backend.maintenance.configuration;

import com.gustler.backend.maintenance.DatabaseEnvironment;
import com.gustler.backend.maintenance.db.DatabaseConnections;
import com.gustler.backend.routecatalog.configuration.StopWalkDistanceImportConfiguration;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** 정류장 쌍 도보 거리 파일을 넣는 명령에 필요한 DB 연결과 적재 기능만 구성한다. */
@Configuration(proxyBeanMethods = false)
@Import(StopWalkDistanceImportConfiguration.class)
@EnableTransactionManagement(proxyTargetClass = true)
public class WalkDistanceImportConfiguration {
    @Bean
    DataSource dataSource(DatabaseEnvironment environment) {
        return DatabaseConnections.dataSource(environment);
    }

    @Bean
    JdbcClient jdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
