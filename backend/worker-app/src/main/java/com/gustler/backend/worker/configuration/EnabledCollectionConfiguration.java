package com.gustler.backend.worker.configuration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 수집 기능을 명시적으로 켠 경우에만 수집에 필요한 구성을 등록한다.
 */
@Configuration
@ConditionalOnExpression("${collection.enabled:false} or ${route-geometry.enabled:false}")
@EnableScheduling
@Import(CollectionRuntimeConfiguration.class)
public class EnabledCollectionConfiguration {
}
