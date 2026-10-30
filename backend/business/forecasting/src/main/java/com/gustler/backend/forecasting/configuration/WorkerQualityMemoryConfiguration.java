package com.gustler.backend.forecasting.configuration;

import com.gustler.backend.forecasting.application.quality.WorkerQualityState;
import com.gustler.backend.forecasting.application.quality.WorkerQualityTransactions;
import com.gustler.backend.forecasting.infrastructure.jdbc.JdbcWorkerQualitySnapshotQuery;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 단일 worker의 자동 품질 변경만 지원한다. 운영 명령이 다른 프로세스에서 품질 버전을 바꾸는 동안에는 DB 조회 경로를 사용한다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression(
    "${forecast.quality-memory.enabled:false} and not ${route-operations.enabled:false}")
public class WorkerQualityMemoryConfiguration {
    @Bean
    WorkerQualityState workerQualityState(
        JdbcClient jdbc,
        @Value("${forecast.quality-memory.max-routes:100}") final int maxRoutes
    ) {
        return new WorkerQualityState(new JdbcWorkerQualitySnapshotQuery(jdbc)::read, maxRoutes);
    }

    @Bean
    WorkerQualityTransactions workerQualityTransactions(WorkerQualityState state) {
        return new WorkerQualityTransactions(state);
    }
}
