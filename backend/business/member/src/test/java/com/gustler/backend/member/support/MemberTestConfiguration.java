package com.gustler.backend.member.support;

import com.gustler.backend.member.configuration.MemberConfiguration;
import com.gustler.backend.support.PostgresTestContainer;
import java.time.ZoneId;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 실행 앱 없이 회원 모듈과 실제 DB 를 검증한다. 모듈이 공개한 구성을 그대로 가져온다. */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@Import({PostgresTestContainer.class, MemberConfiguration.class})
class MemberTestConfiguration {
    @Bean
    AdjustableClock clock() {
        return new AdjustableClock(ZoneId.of("Asia/Seoul"));
    }
}
