package com.gustler.backend.api.http.docs;

import java.util.Arrays;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** 문서 제공 범위를 명시적인 local/dev 프로필로 제한한다. */
public final class ApiDocsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        final boolean localOrDev = Arrays.stream(environment.getActiveProfiles())
            .anyMatch(profile -> profile.equals("api-docs-local") || profile.equals("api-docs-dev"));
        final boolean production = Arrays.stream(environment.getActiveProfiles())
            .anyMatch(profile -> profile.equals("prod") || profile.equals("production"));
        final boolean enabled = localOrDev && !production;

        environment.getPropertySources().addFirst(new MapPropertySource("apiDocsMode", Map.of(
            "springdoc.api-docs.enabled", enabled,
            "springdoc.swagger-ui.enabled", enabled
        )));
    }
}
