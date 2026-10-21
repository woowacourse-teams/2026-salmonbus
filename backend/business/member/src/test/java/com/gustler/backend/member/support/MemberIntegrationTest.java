package com.gustler.backend.member.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(classes = MemberTestConfiguration.class, webEnvironment = WebEnvironment.NONE,
    properties = {
        "spring.datasource.hikari.maximum-pool-size=12",
        "auth.enabled=true",
        "auth.refresh-token.secret=member-module-test-only-refresh-secret-0001"
    })
public @interface MemberIntegrationTest {
}
