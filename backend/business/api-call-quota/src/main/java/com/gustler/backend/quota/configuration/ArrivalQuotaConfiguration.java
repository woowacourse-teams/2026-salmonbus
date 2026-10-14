package com.gustler.backend.quota.configuration;

import com.gustler.backend.quota.api.ArrivalCallQuota;
import com.gustler.backend.quota.api.ArrivalQuotaPolicy;
import com.gustler.backend.quota.application.ArrivalCallQuotaLedger;
import com.gustler.backend.quota.domain.ArrivalQuotaStore;
import com.gustler.backend.quota.infrastructure.jdbc.JdbcArrivalQuotaStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "arrivals", name = "enabled", havingValue = "true")
public class ArrivalQuotaConfiguration {
    @Bean
    ArrivalQuotaStore arrivalQuotaStore(JdbcClient jdbc, PlatformTransactionManager manager) {
        return new JdbcArrivalQuotaStore(jdbc, manager);
    }

    @Bean
    ArrivalCallQuota arrivalCallQuota(ArrivalQuotaStore store, ArrivalQuotaPolicy policy) {
        return new ArrivalCallQuotaLedger(store, policy);
    }
}
