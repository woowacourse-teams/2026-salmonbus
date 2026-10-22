package com.gustler.backend.forecasting.configuration;

import com.gustler.backend.forecasting.application.quality.WorkerQualityState;
import com.gustler.backend.forecasting.application.quality.WorkerQualityTransactions;
import com.gustler.backend.forecasting.infrastructure.jdbc.JdbcWorkerQualitySnapshotQuery;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 단일 worker의 자동 품질 변경만 지원한다. 외부 DB 수정과 다중 worker에서는 끈다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "forecast.quality-memory", name = "enabled", havingValue = "true")
public class WorkerQualityMemoryConfiguration {
    @Bean
    WorkerQualityState workerQualityState(JdbcClient jdbc,
        @Value("${forecast.quality-memory.max-routes:100}") final int maxRoutes) {
        return new WorkerQualityState(new JdbcWorkerQualitySnapshotQuery(jdbc)::read, maxRoutes);
    }

    @Bean
    WorkerQualityTransactions workerQualityTransactions(WorkerQualityState state) {
        return new WorkerQualityTransactions(state);
    }
}
