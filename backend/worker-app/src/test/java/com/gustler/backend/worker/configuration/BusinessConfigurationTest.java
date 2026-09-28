package com.gustler.backend.worker.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.gustler.backend.forecasting.api.ForecastPolicy;
import com.gustler.backend.forecasting.api.evaluation.SameDayInitializationPolicy;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * 예보 신선도 창은 보드가 약속한 창과 짝이라 환경변수로 바꾸지 않는다.
 * 두 앱이 다른 프로세스여서 한쪽만 바뀌어도 기동은 따로 성공하므로, 여기서 멈춰야 한다.
 */
class BusinessConfigurationTest {

    private static final String ENVIRONMENT_NAME = "FORECAST_STALENESS";
    private static final String[] VALID_FORECAST_SETTINGS = {
        "forecast.interval=10s",
        "forecast.settlement-interval=60s",
        "forecast.statistics-interval=6h",
        "forecast.staleness=5m",
        "forecast.batch-limit=20",
        "forecast.pending-limit=3000",
        "forecast.arrival-limit=400"
    };

    @Test
    void 환경변수로_신선도_창을_주면_기동을_멈춘다() {
        // given
        StandardEnvironment environment = environmentWith(Map.of(ENVIRONMENT_NAME, "3m"));

        // when & then
        assertThatIllegalStateException()
            .isThrownBy(() -> BusinessConfiguration.requireStalenessFromConfigurationFile(environment))
            .withMessageContaining(ENVIRONMENT_NAME);
    }

    @Test
    void 다른_환경변수만_있으면_기동한다() {
        // given
        StandardEnvironment environment = environmentWith(Map.of("FORECAST_ENABLED", "false"));

        // when & then
        assertThatCode(() -> BusinessConfiguration.requireStalenessFromConfigurationFile(environment))
            .doesNotThrowAnyException();
    }

    @Test
    void 예보를_켜면_예보_설정을_읽는다() {
        // when & then
        forecastPoliciesRunner(Map.of())
            .withPropertyValues("forecast.enabled=true")
            .withPropertyValues(VALID_FORECAST_SETTINGS)
            .run(context -> assertThat(context)
                .hasSingleBean(ForecastPolicy.class)
                .hasSingleBean(SameDayInitializationPolicy.class));
    }

    @Test
    void 예보를_켜면_환경변수로_신선도_창을_줄_때_기동을_멈춘다() {
        // when & then
        forecastPoliciesRunner(Map.of(ENVIRONMENT_NAME, "3m"))
            .withPropertyValues("forecast.enabled=true")
            .withPropertyValues(VALID_FORECAST_SETTINGS)
            .run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining(ENVIRONMENT_NAME));
    }

    @Test
    void 예보를_끄면_예보_설정이_잘못되거나_신선도_창_환경변수가_있어도_기동한다() {
        // when & then
        forecastPoliciesRunner(Map.of(ENVIRONMENT_NAME, "3m"))
            .withPropertyValues(
                "forecast.enabled=false",
                "forecast.staleness=0s",
                "forecast.batch-limit=0",
                "forecast.same-day-initialization.retry-interval=0ms")
            .run(context -> assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(ForecastProperties.class)
                .doesNotHaveBean(SameDayInitializationProperties.class)
                .doesNotHaveBean(ForecastPolicy.class)
                .doesNotHaveBean(SameDayInitializationPolicy.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "-1h"})
    void 예보를_꺼도_통계_주기가_양수가_아니면_기동을_멈춘다(
        String interval
    ) {
        // when & then
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new BusinessConfiguration().demandStatisticsPolicy(interval));
    }

    private static ApplicationContextRunner forecastPoliciesRunner(
        Map<String, Object> variables
    ) {
        return new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withInitializer(context -> context.getEnvironment().getPropertySources().replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables)))
            .withUserConfiguration(BusinessConfiguration.ForecastPolicies.class);
    }

    private static StandardEnvironment environmentWith(
        Map<String, Object> variables
    ) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
        return environment;
    }
}
