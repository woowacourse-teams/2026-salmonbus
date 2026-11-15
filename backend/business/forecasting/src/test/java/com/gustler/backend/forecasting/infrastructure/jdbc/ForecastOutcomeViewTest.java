package com.gustler.backend.forecasting.infrastructure.jdbc;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.api.pipeline.ForecastPublished;
import com.gustler.backend.forecasting.api.pipeline.PassClosed;
import com.gustler.backend.forecasting.application.pipeline.ForecastRecordStore;
import com.gustler.backend.forecasting.application.pipeline.PassLabel;
import com.gustler.backend.forecasting.application.pipeline.PassLabelStore;
import com.gustler.backend.forecasting.application.pipeline.ScoredForecast;
import com.gustler.backend.forecasting.application.pipeline.ScoringResult;
import com.gustler.backend.forecasting.application.pipeline.ScoringStage;
import com.gustler.backend.forecasting.application.pipeline.StopPassStore;
import com.gustler.backend.forecasting.application.pipeline.UnresolvedForecast;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * 채점 단계가 낸 결과를 새 테이블에 저장한 뒤 예측별 결과 뷰({@code forecast_outcome})가 채점이 짝지은 결과를 그대로 보이는지 본다.
 * 사건과 예보·통과 기록은 모두 저장하고, 채점에는 앞부분만 흘려 아직 대기 중인 예측도 섞는다.
 */
@ForecastingIntegrationTest
@Transactional
class ForecastOutcomeViewTest {
    private static final int LIMIT = 150_000;

    @Autowired
    JdbcClient jdbc;
    @Autowired
    ForecastRecordStore records;
    @Autowired
    StopPassStore passes;
    @Autowired
    PassLabelStore labels;

    @Test
    void 채점_도중의_예측별_결과는_채점이_짝지은_것과_같고_아직_기다리는_예측은_채점_전으로_보인다() {
        // given
        LegacyScoringScenario scenario = LegacyScoringScenario.random(83, Instant.parse("2026-01-04T21:00:00Z"), 400, 7,
            0.002, true);
        List<Object> events = scenario.events();
        store(events);
        ScoringStage stage = scenario.stage(LIMIT);
        ScoringResult result = LegacyScoringScenario.feed(stage, events.subList(0, events.size() * 2 / 3));
        save(result);

        // when
        Map<String, Row> actual = view();

        // then
        Map<String, Row> expected = expected(scenario, result, actual.keySet());
        assertSoftly(softly -> {
            softly.assertThat(actual).isEqualTo(expected);
            softly.assertThat(actual.values()).extracting(Row::state)
                .contains("SETTLED", "UNSUPPORTED", "LOST", "PENDING");
            softly.assertThat(actual.values().stream().filter(row -> row.state().equals("PENDING")).count())
                .isGreaterThanOrEqualTo(stage.waitingCount());
        });
    }

    @Test
    void 마감까지_흘린_뒤에는_모든_예측이_채점이_짝지은_결과를_보인다() {
        // given
        LegacyScoringScenario scenario = LegacyScoringScenario.random(89, Instant.parse("2026-01-05T07:30:00Z"), 400, 7,
            0, true);
        List<Object> events = scenario.events();
        store(events);
        ScoringStage stage = scenario.stage(LIMIT);
        ScoringResult result = LegacyScoringScenario.feed(stage, events)
            .plus(stage.expire(Instant.parse("2026-01-06T00:00:00Z")));
        save(result);

        // when
        Map<String, Row> actual = view();

        // then
        assertSoftly(softly -> {
            softly.assertThat(stage.waitingCount()).isZero();
            softly.assertThat(actual).isEqualTo(expected(scenario, result, actual.keySet()));
            softly.assertThat(actual.values()).extracting(Row::state).doesNotContain("PENDING").contains("SETTLED");
        });
    }

    private void store(List<Object> events) {
        for (Object event : events) {
            if (event instanceof ForecastPublished published) {
                records.save(published);
                continue;
            }
            if (event instanceof PassClosed pass) {
                passes.save(List.of(pass));
            }
        }
    }

    private void save(ScoringResult result) {
        if (!result.labels().isEmpty()) {
            labels.saveLabels(result.labels());
        }
        if (!result.unresolved().isEmpty()) {
            labels.saveUnresolved(result.unresolved());
        }
    }

    private Map<String, Row> view() {
        Map<String, Row> rows = new LinkedHashMap<>();
        jdbc.sql("""
            SELECT vehicle_observation_id, target_stop_order, state, reason, label_value, parameter_hash, capacity,
                model_deployment_id, arrival_observation_id, last_observation_id, closure_observation_id,
                previous_last_observation_id, previous_closure_observation_id
            FROM forecast_outcome WHERE route_id = ?
            """).param(LegacyScoringScenario.ROUTE).query(row -> {
                rows.put(row.getLong(1) + ":" + row.getInt(2), new Row(row.getString(3), row.getString(4),
                    (Double) row.getObject(5), row.getString(6), row.getInt(7), row.getLong(8),
                    (Long) row.getObject(9), (Long) row.getObject(10), (Long) row.getObject(11),
                    (Long) row.getObject(12), (Long) row.getObject(13)));
            });
        return rows;
    }

    /** 채점 결과에서 예측마다 기대하는 줄을 만든다. 결과가 없는 예측은 채점 전이다. */
    private static Map<String, Row> expected(
        LegacyScoringScenario scenario,
        ScoringResult result,
        Iterable<String> predictions
    ) {
        Map<List<Object>, PassLabel> labelByKey = labelsByKey(result);
        Map<List<Object>, PassClosed> passByKey = passesByKey(scenario);
        Map<String, Row> expected = new LinkedHashMap<>();
        for (String prediction : predictions) {
            expected.put(prediction, null);
        }
        for (UnresolvedForecast closed : result.unresolved()) {
            expected.put(closed.vehicleObservationId() + ":" + closed.targetStopOrder(), new Row(closed.state().name(),
                closed.reason(), null, null, capacity(scenario, closed.vehicleObservationId()),
                LegacyScoringScenario.DEPLOYMENT, null, null, null, null, null));
        }
        for (ScoredForecast scored : result.scored()) {
            PassLabel label = labelByKey.get(List.of(scored.passSourceDate(), scored.routeId(),
                scored.tripStartObservationId(), scored.stopOrder(), scored.capacity(), scored.labelVersion(),
                scored.modelDeploymentId()));
            PassClosed pass = passByKey.get(List.of(scored.passSourceDate(), scored.routeId(),
                scored.tripStartObservationId(), scored.stopOrder()));
            expected.put(scored.vehicleObservationId() + ":" + scored.targetStopOrder(), new Row(label.state().name(),
                label.reason(), label.labelValue(), label.parameterHash(), label.capacity(), label.modelDeploymentId(),
                pass.arrivalObservationId(), pass.lastObservationId(), pass.closureObservationId(),
                label.previousLastObservationId(), label.previousClosureObservationId()));
        }
        for (Map.Entry<String, Row> entry : expected.entrySet()) {
            if (entry.getValue() == null) {
                final long observation = Long.parseLong(entry.getKey().split(":")[0]);
                entry.setValue(new Row("PENDING", null, null, null, capacity(scenario, observation),
                    LegacyScoringScenario.DEPLOYMENT, null, null, null, null, null));
            }
        }
        return expected;
    }

    private static Map<List<Object>, PassLabel> labelsByKey(ScoringResult result) {
        Map<List<Object>, PassLabel> labelByKey = new HashMap<>();
        for (PassLabel label : result.labels()) {
            labelByKey.putIfAbsent(List.of(label.sourceDate(), label.routeId(), label.tripStartObservationId(),
                label.stopOrder(), label.capacity(), label.labelVersion(), label.modelDeploymentId()), label);
        }
        return labelByKey;
    }

    private static Map<List<Object>, PassClosed> passesByKey(LegacyScoringScenario scenario) {
        Map<List<Object>, PassClosed> passByKey = new HashMap<>();
        for (PassClosed pass : scenario.passes()) {
            passByKey.put(List.of(pass.sourceDate(), pass.routeId(), pass.tripStartObservationId(), pass.stopOrder()),
                pass);
        }
        return passByKey;
    }

    private static int capacity(LegacyScoringScenario scenario, final long observation) {
        return scenario.forecastCapacity(observation);
    }

    private record Row(
        String state,
        String reason,
        Double labelValue,
        String parameterHash,
        int capacity,
        long modelDeploymentId,
        Long arrival,
        Long last,
        Long closure,
        Long previousLast,
        Long previousClosure
    ) {
    }
}
