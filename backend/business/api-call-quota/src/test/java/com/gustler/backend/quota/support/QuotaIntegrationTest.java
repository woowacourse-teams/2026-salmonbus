package com.gustler.backend.quota.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;

@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(classes = QuotaTestConfiguration.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"spring.datasource.hikari.maximum-pool-size=3"})
public @interface QuotaIntegrationTest {
}
