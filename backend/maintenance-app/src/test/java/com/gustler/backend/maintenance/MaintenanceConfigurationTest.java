package com.gustler.backend.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.forecasting.api.model.LoadConfiguredModel;
import com.gustler.backend.forecasting.api.quality.GetTripQualityStatus;
import com.gustler.backend.forecasting.api.quality.PreviewTripQuality;
import com.gustler.backend.forecasting.api.quality.ProcessTripQualityChunk;
import com.gustler.backend.maintenance.configuration.MaintenanceConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.config.ScheduledTaskHolder;

class MaintenanceConfigurationTest {

    @Test
    void 정비_구성은_DB에_접속하지_않고_공개_품질_기능만_준비한다() {
        // 연결할 수 없는 주소에서도 기동 구성이 DB 변경이나 모델 적재를 시작하지 않아야 한다.
        DatabaseEnvironment environment = new DatabaseEnvironment(DatabaseEnvironment.TargetKind.LOCAL,
            "jdbc:postgresql://127.0.0.1:1/maintenance-test", "unused", "unused", "test");
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DatabaseEnvironment.class, () -> environment);
            context.register(MaintenanceConfiguration.class);
            context.refresh();

            assertThat(context.getBeansOfType(PreviewTripQuality.class)).hasSize(1);
            assertThat(context.getBeansOfType(ProcessTripQualityChunk.class)).hasSize(1);
            assertThat(context.getBeansOfType(GetTripQualityStatus.class)).hasSize(1);
            assertThat(AopUtils.isAopProxy(context.getBean(ProcessTripQualityChunk.class))).isTrue();
            assertThat(context.getBeansOfType(LoadConfiguredModel.class)).isEmpty();
            assertThat(context.getBeansOfType(ScheduledTaskHolder.class)).isEmpty();
            assertThat(context.getBeanDefinitionNames())
                .noneMatch(name -> name.toLowerCase().contains("flyway") || name.toLowerCase().contains("gbis"));
        }
    }

    @Test
    void 폐기한_이관_명령은_정비_구성을_시작하기_전에_거절한다() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            MaintenanceApplication.run(CliArguments.parse(new String[] {"archive-import"})))
            .isInstanceOfSatisfying(MaintenanceException.class,
                error -> assertThat(error.code()).isEqualTo("UNKNOWN_COMMAND"));
    }
}
