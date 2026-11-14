package com.gustler.backend.worker.configuration;

import com.gustler.backend.forecasting.api.pipeline.MaintainPipelinePartitions;
import com.gustler.backend.worker.scheduling.PipelinePartitionJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 새 예보 경로의 스위치 가운데 하나라도 켜졌을 때만 새 테이블의 파티션 유지 작업을 등록한다.
 *
 * <p>켜는 조건은 {@code forecast.shadow.enabled=true}, {@code forecast.pipeline=v2}, {@code forecast.scoring}이
 * {@code off}가 아님 가운데 하나다. 스위치가 모두 기본값이면 이 구성은 빈도 스케줄도 더하지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(ForecastPipelineProperties.class)
@ConditionalOnExpression(ForecastPipelineConfiguration.USES_PIPELINE_TABLES)
public class ForecastPipelineConfiguration {
    /** 스위치 조건. {@link ForecastPipelineProperties#usesPipelineTables()}와 같은 판정이다. */
    static final String USES_PIPELINE_TABLES = "${forecast.shadow.enabled:false}"
        + " or '${forecast.pipeline:v1}'.equalsIgnoreCase('v2')"
        + " or !'${forecast.scoring:off}'.equalsIgnoreCase('off')";

    private static final Logger LOGGER = LoggerFactory.getLogger(ForecastPipelineConfiguration.class);

    @Bean
    PipelinePartitionJob pipelinePartitionJob(MaintainPipelinePartitions partitions) {
        return new PipelinePartitionJob(partitions);
    }

    /** 채점만 켜고 그림자 실행을 켜지 않았으면 채점이 돌지 않는다는 경고를 기동 때 한 번 남긴다. */
    @Bean
    SmartInitializingSingleton scoringSwitchWarning(
        ForecastPipelineProperties properties,
        @Value("${forecast.enabled:false}") final boolean forecastEnabled
    ) {
        return () -> {
            if (properties.scoringWithoutShadow(forecastEnabled)) {
                LOGGER.warn("event=pipeline_scoring_inactive scoring={} forecastEnabled={} shadowEnabled={} pipeline={}",
                    properties.scoring(), forecastEnabled, properties.shadow().enabled(), properties.pipeline());
            }
        };
    }
}
