package com.gustler.backend.forecasting.application.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.forecasting.application.evaluation.EvaluateForecastsService.RouteInput;
import com.gustler.backend.forecasting.application.evaluation.EvaluationSelection.Cursor;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabel.Settled;
import com.gustler.backend.forecasting.domain.evaluation.ForecastEvaluation;
import com.gustler.backend.forecasting.domain.evaluation.PendingForecast;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** 실제 DB의 조사 여부·편도 판정·결과 저장을 확인한다. 바깥 트랜잭션은 fixture 정리용이다. */
@ForecastingIntegrationTest
@Transactional
class EvaluationQualityScopeTest {
    private static final Instant START = Instant.parse("2026-10-01T00:00:00Z");
    @Autowired
    JdbcClient jdbc;
    @Autowired
    EvaluationWorkStore work;
    @Autowired
    EvaluationRouteCommitter committer;
    private long route;
    private long version;
    private long model;
    private Pair firstA;
    private Pair secondA;
    private Pair busB;
    private RouteInput input;
    private List<ForecastEvaluation> calculated;

    @BeforeEach
    void 같은_노선에서_A의_두_편도와_B의_예보를_계산해둔다() {
        String key = "scope-" + UUID.randomUUID().toString().substring(0, 16);
        route = jdbc.sql("""
            INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)
            VALUES(?,'GBIS',?,'범위 검증','출발','도착') RETURNING id
            """).params(key, key).query(Long.class).single();
        version = jdbc.sql("INSERT INTO route_version(route_id,content_digest,valid_from) VALUES(?,?,?) RETURNING id")
            .params(route, "0".repeat(64), offset(START)).query(Long.class).single();
        jdbc.sql("""
            INSERT INTO route_stop(route_version_id,stop_order,stop_id,name,direction,boarding_allowed)
            SELECT ?,n,'stop-'||n,'정류장','UP',true FROM generate_series(1,2) n
            """).param(version).update();
        jdbc.sql("INSERT INTO route_data_quality(route_id) VALUES(?)").param(route).update();
        model = jdbc.sql("""
            INSERT INTO model_deployment(deployment_key,release_id,model_key,model_version,bundle_digest,
                prediction_target_version,calculation_version,supported_scope_digest,data_until,state)
            VALUES(?,?,'seat-full-chance','1.0.0',?,'SEAT_FULL_CHANCE_V1','CALCULATION_V1',?,?,'STAGED') RETURNING id
            """)
            .params(UUID.randomUUID(), key, "0".repeat(64), "0".repeat(64), offset(START))
            .query(Long.class)
            .single();
        firstA = pair("A", START);
        secondA = pair("A", START.plusSeconds(600));
        busB = pair("B", START);
        List<Long> ids = List.of(firstA.source(), secondA.source(), busB.source());
        List<PendingForecast> forecasts = work.restore(version, ids)
            .stream()
            .map(row -> row.forecast().orElseThrow())
            .toList();
        input = new RouteInput(version, 1, false, Cursor.START,
            ids, work.revisions(version, ids), List.of(), forecasts);
        calculated = List.of(firstA, secondA, busB)
            .stream()
            .map(pair -> ForecastEvaluation.completed(
                pair.source(), 2, new Settled(pair.arrival(), 12), START.plusSeconds(660)))
            .toList();
    }

    @Test
    void A를_조사하는_동안에도_B의_정산은_저장하고_A의_작업은_남긴다() {
        // given
        investigate(false);

        // when
        committer.commit(input, calculated, (status, change) -> {
        });

        // then
        assertThat(result(busB)).isEqualTo("SETTLED");
        assertThat(result(firstA)).isNull();
        assertThat(result(secondA)).isNull();
        assertThat(work.page(version, 0, Long.MAX_VALUE, 100))
            .containsExactly(firstA.source(), secondA.source());
    }

    @Test
    void 조사가_끝나면_A의_제외된_편도만_닫고_다른_편도와_B는_정산한다() {
        // given
        investigate(true);
        jdbc.sql("UPDATE vehicle_one_way_trip SET status='EXCLUDED' WHERE id=?")
            .param(Long.toString(firstA.source())).update();

        // when
        committer.commit(input, calculated, (status, change) -> {
        });

        // then
        assertThat(result(firstA)).isEqualTo("QUALITY_EXCLUDED");
        assertThat(result(secondA)).isEqualTo("SETTLED");
        assertThat(result(busB)).isEqualTo("SETTLED");
        assertThat(work.page(version, 0, Long.MAX_VALUE, 100)).isEmpty();
    }

    @Test
    void 예보_원본이_정상이어도_후속_관측의_좌석이_달라졌으면_기존_계산은_저장하지_않는다() {
        // given
        jdbc.sql("UPDATE route_data_quality SET quality_revision=2 WHERE route_id=?").param(route).update();
        jdbc.sql("UPDATE vehicle_observation SET remaining_seats=0 WHERE id=?").param(firstA.arrival()).update();

        // when
        committer.commit(input, calculated, (status, change) -> {
        });

        // then
        assertThat(result(firstA)).isNull();
        assertThat(result(secondA)).isEqualTo("SETTLED");
        assertThat(result(busB)).isEqualTo("SETTLED");
        assertThat(work.page(version, 0, Long.MAX_VALUE, 100)).containsExactly(firstA.source());
    }

    private void investigate(final boolean completed) {
        jdbc.sql("""
            INSERT INTO trip_quality_rebuild(route_version_id,vehicle_id,until_at,completed,phase)
            VALUES(?,'A',?,?,?)
            """).params(version, offset(START.plusSeconds(30)), completed, phaseOf(completed)).update();
        jdbc.sql("UPDATE route_data_quality SET quality_revision=2 WHERE route_id=?").param(route).update();
    }

    private Pair pair(String vehicle, Instant at) {
        final long source = observation(vehicle, at, 1);
        final long arrival = observation(vehicle, at.plusSeconds(30), 2);
        jdbc.sql("""
            INSERT INTO vehicle_one_way_trip(id,start_observation_id,route_version_id,vehicle_id,status,boundary,rule_version)
            VALUES(?,?,?,?,'ELIGIBLE','DEPARTURE','test')
            """)
            .params(Long.toString(source), source, version, vehicle)
            .update();
        for (final long id : List.of(source, arrival)) {
            jdbc.sql("INSERT INTO observation_trip_assignment(observation_id,trip_id) VALUES(?,?)")
                .params(id, Long.toString(source)).update();
        }
        final long publication = jdbc.sql("""
            INSERT INTO forecast_publication(source_batch_id,route_version_id,model_deployment_id,
                demand_statistics_revision,quality_revision,observed_at,generated_at,published_at,prediction_count)
            SELECT observation_batch_id,?, ?,1,1,?,?,?,1 FROM vehicle_observation WHERE id=? RETURNING id
            """).params(version, model, offset(at), offset(at), offset(at), source).query(Long.class).single();
        jdbc.sql("""
            INSERT INTO seat_forecast(vehicle_observation_id,target_stop_order,route_version_id,stops_to_target,
                model_deployment_id,demand_statistics_revision,quality_revision,seat_full_chance_raw,
                seat_full_chance,expected_seats,generated_at,publication_id)
            VALUES(?,2,?,1,?,1,1,0.4,0.4,12,?,?)
            """).params(source, version, model, offset(at), publication).update();
        work.register(version, List.of(source));
        return new Pair(source, arrival);
    }

    private long observation(String vehicle, Instant at, final int stop) {
        final long batch = jdbc.sql("""
            INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,requested_at,
                response_received_at,outcome,normalization_version,collection_strategy_version)
            VALUES(?,?,1,?,?,?,'SUCCESS_ROWS','test','test') RETURNING id
            """)
            .params(version, offset(at), UUID.randomUUID()
                .toString(), offset(at), offset(at))
            .query(Long.class)
            .single();
        return jdbc.sql("""
            INSERT INTO vehicle_observation(observation_batch_id,route_version_id,source_row_number,vehicle_id,
                stop_order,stop_id,running_state,passed_stop_order,remaining_seats)
            VALUES(?,?,1,?,?,'stop-'||CAST(? AS text),2,?,12) RETURNING id
            """).params(batch, version, vehicle, stop, stop, stop).query(Long.class).single();
    }

    private String result(Pair pair) {
        return jdbc.sql("SELECT scoring_state FROM forecast_evaluation_result WHERE vehicle_observation_id=?")
            .param(pair.source()).query(String.class).optional().orElse(null);
    }

    private static OffsetDateTime offset(Instant at) {
        return at.atOffset(ZoneOffset.UTC);
    }
    private record Pair(long source, long arrival) {
    }

    private static String phaseOf(final boolean completed) {
        if (completed) {
            return "DONE";
        }
        return "SEARCH_START";
    }
}
