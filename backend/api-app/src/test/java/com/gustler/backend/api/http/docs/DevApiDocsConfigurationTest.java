package com.gustler.backend.api.http.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

class DevApiDocsConfigurationTest {

    @Test
    void 실제_dev_설정은_문서_프로필을_포함한다() {
        try (var context = start()) {
            assertThat(context.getEnvironment().getActiveProfiles()).contains("api-docs-dev");
            assertThat(context.getEnvironment().getProperty("springdoc.api-docs.enabled", Boolean.class)).isTrue();
            assertThat(context.getEnvironment().getProperty("springdoc.swagger-ui.enabled", Boolean.class)).isTrue();
        }
    }

    @Test
    void 실제_dev_설정을_읽어도_운영_프로필에서는_문서를_제공하지_않는다() {
        try (var context = start("--spring.profiles.active=prod")) {
            assertThat(context.getEnvironment().getActiveProfiles()).contains("api-docs-dev", "prod");
            assertThat(context.getEnvironment().getProperty("springdoc.api-docs.enabled", Boolean.class)).isFalse();
            assertThat(context.getEnvironment().getProperty("springdoc.swagger-ui.enabled", Boolean.class)).isFalse();
        }
    }

    private ConfigurableApplicationContext start(String... additionalArguments) {
        var application = new SpringApplication(EmptyApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setLogStartupInfo(false);
        var arguments = Stream.concat(
            Stream.of("--spring.config.additional-location=" + devApiConfig().toUri()),
            Arrays.stream(additionalArguments)
        ).toArray(String[]::new);
        return application.run(arguments);
    }

    private Path devApiConfig() {
        var directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            var candidate = directory.resolve("dev/server/api.yml");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("dev/server/api.yml을 찾을 수 없습니다.");
    }

    @Configuration(proxyBeanMethods = false)
    static class EmptyApplication {
    }
}
