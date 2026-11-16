package com.gustler.backend.forecasting.infrastructure.jdbc;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.api.ForecastTelemetry;
import com.gustler.backend.forecasting.application.forecast.ForecastRecordStore;
import com.gustler.backend.forecasting.application.forecast.StopPassStore;
import com.gustler.backend.forecasting.domain.deployment.ModelDeploymentRepository;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예보가 배치를 커밋할 때마다 진단 수신자에 알리는 값을 실제 DB로 본다. 이 알림이 노선별 마지막 예보 커밋 시각 지표를 갱신한다.
 */
@ForecastingIntegrationTest
@Transactional
class ForecastTelemetryTest {
    @Autowired
    JdbcClient jdbc;
    @Autowired
    PlatformTransactionManager transactions;
    @Autowired
    ModelDeploymentRepository deployments;
    @Autowired
    ForecastRecordStore records;
    @Autowired
    StopPassStore passes;

    @Test
    void 예보_실행을_저장한_배치마다_수신_시각과_예측_수를_한_번씩_알리고_입력_부족으로_끝난_배치는_알리지_않는다() {
        // given
        ForecastHarness harness = new ForecastHarness(
            new ForecastHarness.Beans(jdbc, transactions, deployments, records, passes), 20261304L,
            Duration.ofMinutes(60));
        harness.start();
        harness.fromVerifiedOrigin();
        RecordingTelemetry telemetry = new RecordingTelemetry();
        harness.telemetry(telemetry);
        List<Long> batches = harness.batches();
        final int split = batches.size() / 2;

        // when
        batches.subList(0, split).forEach(harness::signal);
        harness.dropState();
        harness.failNextReplay();
        batches.subList(split, batches.size()).forEach(harness::signal);
        batches.forEach(harness::signal);

        // then
        List<Published> committed = committedRuns(batches);
        assertSoftly(softly -> {
            softly.assertThat(telemetry.published).containsExactlyInAnyOrderElementsOf(committed);
            softly.assertThat(committed).as("예측이 있는 커밋").anyMatch(run -> run.predictions() > 0);
            softly.assertThat(committed).as("예측이 없는 커밋").anyMatch(run -> run.predictions() == 0);
            softly.assertThat(runs(batches, "= 'INPUT_UNAVAILABLE'")).as("입력 부족으로 끝난 실행").isNotEmpty();
            softly.assertThat(committed.size() + runs(batches, "= 'INPUT_UNAVAILABLE'").size())
                .as("모든 배치에 실행 줄이 하나씩 있다").isEqualTo(batches.size());
        });
    }

    /** 입력 부족이 아닌 예보 실행이다. 실행마다 알림이 한 번 와야 한다. */
    private List<Published> committedRuns(List<Long> batches) {
        return runs(batches, "<> 'INPUT_UNAVAILABLE'");
    }

    private List<Published> runs(List<Long> batches, String outcomeCondition) {
        return jdbc.sql("""
            SELECT route_id, route_version_id, received_at, prediction_count FROM forecast_run
            WHERE observation_batch_id IN (:batches) AND outcome
            """ + outcomeCondition + " ORDER BY confirmed_at, observation_batch_id")
            .param("batches", batches)
            .query((row, index) -> new Published(row.getLong(1), row.getLong(2),
                row.getObject(3, OffsetDateTime.class).toInstant(), row.getInt(4)))
            .list();
    }

    private record Published(long routeId, long routeVersionId, Instant observedAt, int predictions) {
    }

    private static final class RecordingTelemetry implements ForecastTelemetry {
        private final List<Published> published = new ArrayList<>();

        @Override
        public void published(
            final long routeId,
            final long routeVersionId,
            Instant observedAt,
            final int predictions
        ) {
            published.add(new Published(routeId, routeVersionId, observedAt, predictions));
        }
    }
}
