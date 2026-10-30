package com.gustler.backend.api.account.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.gustler.backend.member.api.AnalyticsDeletions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AmplitudeDeletionConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withUserConfiguration(AmplitudeDeletionConfiguration.class)
        .withBean(AnalyticsDeletions.class, () -> mock(AnalyticsDeletions.class));

    @Test
    void 로그인만_켜도_키가_없으면_기동하지_않는다() {
        context.withPropertyValues("auth.enabled=true")
            .run(actual -> assertThat(actual).hasFailed());
    }

    @Test
    void 키를_환경_설정으로_받아_삭제_작업을_등록한다() {
        context.withPropertyValues("auth.enabled=true",
                "analytics.amplitude.api-key=key", "analytics.amplitude.secret-key=secret")
            .run(actual -> assertThat(actual).hasSingleBean(AmplitudeDeletionProcessor.class));
    }

    @Test
    void 로그인을_꺼두면_키_없이_기동한다() {
        context.withPropertyValues("auth.enabled=false").run(actual -> {
            assertThat(actual).doesNotHaveBean(AmplitudeDeletionProcessor.class);
            assertThat(actual).doesNotHaveBean(AmplitudeDeletionClient.class);
        });
    }
}
