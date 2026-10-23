package com.gustler.backend.forecasting.support;

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
@SpringBootTest(
    classes = ForecastingTestConfiguration.class,
    webEnvironment = WebEnvironment.NONE,
    properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
        "spring.datasource.hikari.maximum-pool-size=3",
        "spring.datasource.hikari.minimum-idle=1",
        "forecast.enabled=true",
        "forecast.staleness=5m",
        "forecast.batch-limit=20",
        "forecast.pending-limit=3000",
        "forecast.arrival-limit=400"
    }
)
public @interface ForecastingIntegrationTest {
}
