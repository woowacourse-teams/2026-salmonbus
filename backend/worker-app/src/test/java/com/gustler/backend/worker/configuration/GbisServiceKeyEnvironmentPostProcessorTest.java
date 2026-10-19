package com.gustler.backend.worker.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.gbis.api.GbisKey;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class GbisServiceKeyEnvironmentPostProcessorTest {

    @Test
    void 운영처럼_GBIS_SERVICE_KEY_뒤에_별칭을_붙인_키를_모두_읽는다() {
        // given
        Map<String, Object> variables = new HashMap<>();
        variables.put("GBIS_SERVICE_KEY", "fake-key-a");
        for (String alias : List.of("B", "C", "D", "E", "F", "G")) {
            variables.put("GBIS_SERVICE_KEY_" + alias, "fake-key-" + alias);
        }

        // when
        try (ConfigurableApplicationContext context = applicationWith(variables).run()) {
            GbisProperties properties = context.getBean(GbisProperties.class);

            // then
            assertThat(aliasesOf(properties)).containsExactly(GbisKey.PRIMARY, "b", "c", "d", "e", "f", "g");
            assertThat(properties.serviceKeys().get(4).serviceKey()).isEqualTo("fake-key-E");
        }
    }

    @Test
    void 새로_넣은_별칭도_GBIS_SERVICE_KEY_형식이면_코드_수정_없이_읽는다() {
        // given
        Map<String, Object> variables = Map.of(
            "GBIS_SERVICE_KEY", "fake-key-a",
            "GBIS_SERVICE_KEY_TEAM5", "fake-key-team5",
            "GBIS_KEYS_H", "fake-key-h");

        // when
        try (ConfigurableApplicationContext context = applicationWith(variables).run()) {

            // then
            assertThat(aliasesOf(context.getBean(GbisProperties.class)))
                .containsExactly(GbisKey.PRIMARY, "h", "team5");
        }
    }

    @Test
    void b_c_d는_기존_설정이_읽으므로_다시_옮기지_않는다() {
        // given
        StandardEnvironment environment = environmentWith(Map.of(
            "GBIS_SERVICE_KEY", "fake-key-a",
            "GBIS_SERVICE_KEY_B", "fake-key-b",
            "GBIS_SERVICE_KEY_E", "fake-key-e"));

        // when
        new GbisServiceKeyEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());

        // then
        PropertySource<?> moved = environment.getPropertySources()
            .get(GbisServiceKeyEnvironmentPostProcessor.PROPERTY_SOURCE);
        assertThat(moved).isNotNull();
        assertThat(moved.getProperty("gbis.named-keys[e]")).isEqualTo("fake-key-e");
        assertThat(moved.getProperty("gbis.named-keys[b]")).isNull();
    }

    @Test
    void 값이_빈_GBIS_SERVICE_KEY_별칭은_없는_것으로_본다() {
        // given
        StandardEnvironment environment = environmentWith(Map.of(
            "GBIS_SERVICE_KEY", "fake-key-a",
            "GBIS_SERVICE_KEY_E", " "));

        // when
        new GbisServiceKeyEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());

        // then
        assertThat(environment.getPropertySources().get(GbisServiceKeyEnvironmentPostProcessor.PROPERTY_SOURCE))
            .isNull();
    }

    @Test
    void 별칭이_대소문자만_다른_변수가_둘이면_키_값_없이_기동을_막는다() {
        // given
        StandardEnvironment environment = environmentWith(Map.of(
            "GBIS_SERVICE_KEY", "fake-key-a",
            "GBIS_SERVICE_KEY_E", "fake-key-upper",
            "GBIS_SERVICE_KEY_e", "fake-key-lower"));
        GbisServiceKeyEnvironmentPostProcessor processor = new GbisServiceKeyEnvironmentPostProcessor();

        // when & then
        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, new SpringApplication()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("e")
            .hasMessageNotContaining("fake-key-upper")
            .hasMessageNotContaining("fake-key-lower");
    }

    private static SpringApplication applicationWith(
        Map<String, Object> variables
    ) {
        SpringApplication application = new SpringApplication(GbisOnly.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        Map<String, Object> environment = new HashMap<>(variables);
        environment.put("GBIS_BASE_URL", "https://gbis.test");
        environment.put("GBIS_DAILY_LIMIT", "10000");
        application.setEnvironment(environmentWith(environment));
        return application;
    }

    private static StandardEnvironment environmentWith(
        Map<String, Object> variables
    ) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
        return environment;
    }

    private static List<String> aliasesOf(
        GbisProperties properties
    ) {
        return properties.serviceKeys().stream().map(GbisKey::alias).toList();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GbisProperties.class)
    static class GbisOnly {
    }
}
