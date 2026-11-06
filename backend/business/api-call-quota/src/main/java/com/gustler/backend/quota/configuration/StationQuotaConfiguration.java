package com.gustler.backend.quota.configuration;

import com.gustler.backend.quota.api.StationCallQuota;
import com.gustler.backend.quota.application.StationCallQuotaLedger;
import com.gustler.backend.quota.domain.CallQuotaStore;
import com.gustler.backend.quota.infrastructure.jdbc.JdbcCallQuotaStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/**
 * 위치 수집이 꺼진 경우에도 공통 예약 저장소만 등록한다. 기존 전체 스캔과 같은 빈을 재사용한다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "stations.fill", name = "enabled", havingValue = "true")
@ComponentScan(
    basePackageClasses = JdbcCallQuotaStore.class,
    useDefaultFilters = false,
    includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JdbcCallQuotaStore.class)
)
public class StationQuotaConfiguration {

    @Bean
    StationCallQuota stationCallQuota(CallQuotaStore store) {
        return new StationCallQuotaLedger(store);
    }
}
