package com.gustler.backend.quota.support;

import com.gustler.backend.quota.api.CallQuotaPolicy;
import com.gustler.backend.quota.configuration.CallQuotaConfiguration;
import com.gustler.backend.support.PostgresTestContainer;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 호출 횟수 예약만 검증한다. 모듈이 공개한 구성을 그대로 가져온다. */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@Import({PostgresTestContainer.class, CallQuotaConfiguration.class})
class QuotaTestConfiguration {

    @Bean
    CallQuotaPolicy callQuotaPolicy() {
        return CallQuotaPolicy.sameForEveryApi(10_000);
    }
}
