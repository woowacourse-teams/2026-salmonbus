package com.gustler.backend.worker.configuration;

import com.gustler.backend.forecasting.api.pipeline.MaintainPipelinePartitions;
import com.gustler.backend.worker.scheduling.PipelinePartitionJob;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 날짜 파티션 유지 작업을 등록한다.
 *
 * <p>등록 조건은 둘 가운데 하나다.
 * <ul>
 *   <li>예보가 켜짐({@code forecast.enabled=true}). 예보 기록 테이블 다섯 개의 날짜 파티션을 미리 만든다.</li>
 *   <li>수집이 켜짐({@code collection.enabled=true}). 관측 두 테이블({@code observation_batch}, {@code vehicle_observation})이
 *       파티션 테이블이면 날짜 파티션을 만들어야 한다. 일반 테이블인 동안에는 카탈로그만 읽고 아무것도 쓰지 않는다.</li>
 * </ul>
 * 수집과 예보를 모두 끈 worker는 이 구성이 빈도 스케줄도 더하지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(ForecastPipelineProperties.class)
@ConditionalOnExpression(ForecastPipelineConfiguration.MAINTAINS_PARTITIONS)
public class ForecastPipelineConfiguration {
    /** 유지 작업의 등록 조건. */
    static final String MAINTAINS_PARTITIONS = "${forecast.enabled:false} or ${collection.enabled:false}";

    @Bean
    PipelinePartitionJob pipelinePartitionJob(MaintainPipelinePartitions partitions) {
        return new PipelinePartitionJob(partitions);
    }
}
