package com.gustler.backend.quota.configuration;

import com.gustler.backend.quota.api.ArrivalCallQuota;
import com.gustler.backend.quota.api.ArrivalQuotaPolicy;
import com.gustler.backend.quota.infrastructure.jdbc.JdbcArrivalCallQuota;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "arrivals", name = "enabled", havingValue = "true")
public class ArrivalQuotaConfiguration {
    @Bean
    ArrivalCallQuota arrivalCallQuota(JdbcClient jdbc, PlatformTransactionManager manager, ArrivalQuotaPolicy policy) {
        return new JdbcArrivalCallQuota(jdbc, manager, policy);
    }
}
