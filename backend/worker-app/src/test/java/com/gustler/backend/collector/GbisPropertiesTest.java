package com.gustler.backend.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class GbisPropertiesTest {

    private static final String BASE_URL = "https://gbis.test";
    private static final int DAILY_LIMIT = 10_000;

    /** 2025-08-21 전에 발급된 인증키는 base64 라 {@code +} {@code /} {@code =} 가 섞인다. */
    private static final String ORIGINAL_KEY = "ab+cd/ef=";
    private static final String PERCENT_KEY = "ab%2Bcd%2Fef%3D";
    private static final String KEY_TAIL = "SECRETTAIL";

    @Test
    void 퍼센트로_바뀐_인증키를_원래_값으로_되돌린다() {
        GbisProperties actual = new GbisProperties(BASE_URL, PERCENT_KEY, DAILY_LIMIT);

        assertThat(actual.serviceKey()).isEqualTo(ORIGINAL_KEY);
    }

    @Test
    void 원래_값으로_받은_인증키는_그대로_둔다() {
        GbisProperties actual = new GbisProperties(BASE_URL, ORIGINAL_KEY, DAILY_LIMIT);

        assertThat(actual.serviceKey()).isEqualTo(ORIGINAL_KEY);
    }

    @Test
    void 퍼센트_형식이_깨진_인증키는_기동을_막는다() {
        assertThatThrownBy(() -> new GbisProperties(BASE_URL, "ab%G0" + KEY_TAIL, DAILY_LIMIT))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 퍼센트_형식이_깨져도_인증키가_예외에_남지_않는다() {
        Throwable thrown = catchThrowableOf("ab%G0" + KEY_TAIL);

        assertThat(messagesOf(thrown)).doesNotContain(KEY_TAIL);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void 인증키가_없거나_비었거나_공백뿐이면_기동을_막는다(
        String serviceKey
    ) {
        assertThatThrownBy(() -> new GbisProperties(BASE_URL, serviceKey, DAILY_LIMIT))
            .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void 인증키가_비었거나_공백뿐이면_애플리케이션_컨텍스트가_뜨지_않는다(
        String serviceKey
    ) {
        contextRunner()
            .withPropertyValues(
                "gbis.base-url=" + BASE_URL,
                "gbis.service-key=" + serviceKey,
                "gbis.daily-limit=" + DAILY_LIMIT)
            .run(context -> assertThat(context).hasFailed());
    }

    /**
     * 환경변수가 아예 없는 상태를 만든다.
     *
     * <p>테스트 JVM 에서 GBIS_SERVICE_KEY 를 지울 수는 없다. build.gradle 이 넣고,
     * 스프링은 그 환경변수를 gbis.service-key 에 바로 붙인다. 그래서 환경변수 원본을 안 보는
     * Environment 를 만들어 application.yml 만 얹고 자리표시자가 무엇으로 풀리는지 본다.
     */
    private String serviceKeyWithoutEnvironmentVariable() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources()
            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        try {
            new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addLast);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return environment.getProperty("gbis.service-key");
    }

    @Test
    void 환경변수가_없으면_인증키가_빈_값으로_풀린다() {
        assertThat(serviceKeyWithoutEnvironmentVariable()).isEmpty();
    }

    @Test
    void 환경변수가_없으면_기동을_막는다() {
        String resolved = serviceKeyWithoutEnvironmentVariable();

        assertThatThrownBy(() -> new GbisProperties(BASE_URL, resolved, DAILY_LIMIT))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 인증키가_있으면_애플리케이션_컨텍스트가_뜬다() {
        contextRunner()
            .withPropertyValues(
                "gbis.base-url=" + BASE_URL,
                "gbis.service-key=" + PERCENT_KEY,
                "gbis.daily-limit=" + DAILY_LIMIT)
            .run(context -> assertThat(context).hasNotFailed());
    }

    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
            .withConfiguration(
                org.springframework.boot.autoconfigure.AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(EnableGbisProperties.class);
    }

    private Throwable catchThrowableOf(
        String serviceKey
    ) {
        try {
            new GbisProperties(BASE_URL, serviceKey, DAILY_LIMIT);
            throw new AssertionError("예외가 안 났다");
        } catch (RuntimeException e) {
            return e;
        }
    }

    /** 메시지뿐 아니라 원인 예외까지 훑는다. 원인에 인증키가 실려 오는 것이 이 테스트가 막는 것이다. */
    private String messagesOf(
        Throwable thrown
    ) {
        StringBuilder joined = new StringBuilder();
        for (Throwable each = thrown; each != null; each = each.getCause()) {
            joined.append(each.getMessage()).append(' ').append(each.getClass().getName()).append(' ');
        }
        return joined.toString();
    }

    @org.springframework.boot.context.properties.EnableConfigurationProperties(GbisProperties.class)
    static class EnableGbisProperties {
    }
}
