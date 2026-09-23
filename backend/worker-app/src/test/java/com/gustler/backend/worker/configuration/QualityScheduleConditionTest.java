package com.gustler.backend.worker.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.forecasting.api.quality.InvestigateTripQuality;
import com.gustler.backend.worker.scheduling.TripQualityInvestigationJob;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class QualityScheduleConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(TripQualityInvestigationJob.class)
        .withBean(InvestigateTripQuality.class, () -> () -> {});

    @Test
    void 별도_설정이_없으면_수집이나_예보가_켜질_때_품질_조사를_등록한다() {
        for (String property : new String[] {"collection.enabled=true", "forecast.enabled=true"}) {
            runner.withPropertyValues(property).run(context ->
                assertThat(context).hasSingleBean(TripQualityInvestigationJob.class));
        }
    }

    @Test
    void 명시적으로_끄면_수집과_예보가_켜져도_품질_조사를_등록하지_않는다() {
        runner.withPropertyValues("collection.enabled=true", "forecast.enabled=true", "forecast.quality-enabled=false")
            .run(context -> assertThat(context).doesNotHaveBean(TripQualityInvestigationJob.class));
    }

    @Test
    void 명시적으로_켜면_수집과_예보를_꺼도_품질_조사를_등록한다() {
        runner.withPropertyValues("collection.enabled=false", "forecast.enabled=false", "forecast.quality-enabled=true")
            .run(context -> assertThat(context).hasSingleBean(TripQualityInvestigationJob.class));
    }

    @Test
    void 모두_꺼져_있으면_품질_조사를_등록하지_않는다() {
        runner.run(context -> assertThat(context).doesNotHaveBean(TripQualityInvestigationJob.class));
    }
}
