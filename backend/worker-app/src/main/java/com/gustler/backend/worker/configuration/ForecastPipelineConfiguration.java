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
 *   <li>새 예보 경로 스위치 하나라도 켜짐({@code forecast.shadow.enabled=true}, {@code forecast.pipeline=v2},
 *       {@code forecast.scoring}이 {@code off}가 아님). 새 예보 테이블 다섯 개를 다룬다.</li>
 *   <li>수집이 켜짐({@code collection.enabled=true}). 관측 두 테이블({@code observation_batch}, {@code vehicle_observation})이
 *       운영 전환 마지막 단계의 수동 SQL로 파티션 테이블이 된 뒤에는 스위치와 무관하게 날짜 파티션을 만들어야 한다. 일반 테이블인
 *       동안에는 카탈로그만 읽고 아무것도 쓰지 않는다. 새 예보 테이블은 스위치가 켜졌을 때만 다룬다.</li>
 * </ul>
 * 수집·예보를 모두 끄고 스위치가 기본값인 worker는 이 구성이 빈도 스케줄도 더하지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(ForecastPipelineProperties.class)
@ConditionalOnExpression(ForecastPipelineConfiguration.MAINTAINS_PARTITIONS)
public class ForecastPipelineConfiguration {
    /** 스위치 조건. {@link ForecastPipelineProperties#usesPipelineTables()}와 같은 판정이다. */
    static final String USES_PIPELINE_TABLES = "${forecast.shadow.enabled:false}"
        + " or '${forecast.pipeline:v1}'.equalsIgnoreCase('v2')"
        + " or !'${forecast.scoring:off}'.equalsIgnoreCase('off')";
    /** 유지 작업의 등록 조건. 관측 테이블이 파티션이 된 뒤를 위해 수집이 켜진 worker도 포함한다. */
    static final String MAINTAINS_PARTITIONS = USES_PIPELINE_TABLES + " or ${collection.enabled:false}";

    @Bean
    PipelinePartitionJob pipelinePartitionJob(MaintainPipelinePartitions partitions) {
        return new PipelinePartitionJob(partitions);
    }
}
