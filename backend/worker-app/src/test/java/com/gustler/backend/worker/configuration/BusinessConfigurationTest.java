package com.gustler.backend.worker.configuration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * 예보 신선도 창은 보드가 약속한 창과 짝이라 환경변수로 바꾸지 않는다.
 * 두 앱이 다른 프로세스여서 한쪽만 바뀌어도 기동은 따로 성공하므로, 여기서 멈춰야 한다.
 */
class BusinessConfigurationTest {

    private static final String ENVIRONMENT_NAME = "FORECAST_STALENESS";

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
