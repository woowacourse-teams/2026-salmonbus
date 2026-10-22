package com.gustler.backend.worker.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

@ExtendWith(OutputCaptureExtension.class)
class GbisSettingsStartupFailureTest {
    private static final String SECRET = "fake-key-must-not-be-printed";

    static Stream<Arguments> 잘못된_키_설정() {
        return Stream.of(
            Arguments.of("한 줄 목록", Map.of("GBIS_SERVICE_KEY", "fake-key-a", "GBIS_KEYS", "e=" + SECRET)),
            Arguments.of("별칭 중복", Map.of("GBIS_SERVICE_KEY", SECRET, "GBIS_KEYS_A", "fake-key-other")),
            Arguments.of("깨진 인코딩", Map.of("GBIS_KEYS_A", "fake-key-a", "GBIS_KEYS_E", "ab%G0" + SECRET)),
            Arguments.of("같은 키", Map.of("GBIS_KEYS_A", SECRET, "GBIS_KEYS_E", SECRET)),
            Arguments.of("두 형식의 같은 별칭", Map.of("GBIS_SERVICE_KEY", "fake-key-a", "GBIS_SERVICE_KEY_E", SECRET,
                "GBIS_KEYS_E", "fake-key-other")),
            Arguments.of("별칭 형식", Map.of("GBIS_SERVICE_KEY", "fake-key-a", "GBIS_SERVICE_KEY_TEAM_5", SECRET)),
            Arguments.of("같은 키를 별칭 둘에", Map.of("GBIS_SERVICE_KEY", SECRET, "GBIS_SERVICE_KEY_E", SECRET)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("잘못된_키_설정")
    void 키_설정이_잘못돼_기동하지_못해도_로그에_키_값을_남기지_않는다(
        String ignoredCase,
        Map<String, String> variables,
        CapturedOutput output
    ) {
        // given
        Map<String, Object> environment = new HashMap<>(variables);
        environment.put("GBIS_BASE_URL", "https://gbis.test");
        environment.put("GBIS_DAILY_LIMIT", "10000");
        SpringApplication application = new SpringApplication(GbisOnly.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        StandardEnvironment standard = new StandardEnvironment();
        standard.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                environment));
        application.setEnvironment(standard);

        // when
        Throwable caughtFailure = catchThrowable(application::run);

        // then
        assertThat(caughtFailure)
            .isNotNull().isInstanceOf(RuntimeException.class);
        assertThat(output.getAll())
            .contains("GBIS 설정")
            .contains("GBIS_KEYS_<별칭>")
            .doesNotContain(SECRET);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GbisProperties.class)
    static class GbisOnly {
    }
}
