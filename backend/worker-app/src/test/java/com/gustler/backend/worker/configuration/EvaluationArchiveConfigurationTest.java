package com.gustler.backend.worker.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.forecasting.api.evaluation.AdvanceEvaluationArchive;
import com.gustler.backend.forecasting.application.statistics.StatisticsArchiveReader;
import com.gustler.backend.forecasting.configuration.EvaluationArchiveConfiguration;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class EvaluationArchiveConfigurationTest {
    @TempDir
    Path directory;

    @Test
    void 자동_이관을_꺼도_기존_S3_자료를_읽는_기능은_남는다() {
        // given
        ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(EvaluationArchiveConfiguration.class)
                .withPropertyValues("forecast.archive.enabled=false", "forecast.archive.work-directory=" + directory);

        // when
        runner.run(context -> {

            // then
            assertThat(context).hasNotFailed().doesNotHaveBean(AdvanceEvaluationArchive.class);
            assertThat(context.getBeanProvider(StatisticsArchiveReader.class).getIfAvailable()).isNotNull();
        });
    }

    @Test
    void 이관_자료를_읽지_않는_서버는_작업_폴더가_없어도_시작한다() {
        // given
        ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(EvaluationArchiveConfiguration.class)
                .withPropertyValues("forecast.archive.enabled=false",
                    "forecast.archive.work-directory=" + directory.resolve("absent"));

        // when
        runner.run(context -> {

            // then
            assertThat(context).hasNotFailed().doesNotHaveBean(AdvanceEvaluationArchive.class);
        });
    }
}
