package com.gustler.backend.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 배치에서 출발하도록 바꾼 최근 기록 판정이 예보·정산에서 출발하던 이전 질의와 같은 노선·날짜를 막는지 대조한다.
 *
 * <p>이전 질의는 대조 기준으로만 이 시험에 남긴다. 이전 단계의 판정(수집·정산 대기)에 걸리지 않는 자료만 만든다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ArchiveReadinessQueryTest {
    private static final LocalDate DATE = LocalDate.parse("2026-11-07");
    private static final Instant CUTOFF = Instant.parse("2026-11-07T15:00:00Z");
    private static final String OLD = "2026-11-07T03:00Z";
    private static final String RECENT = "2026-11-07T15:00Z";
    private static final String PREVIOUS_RECENT = """
        SELECT EXISTS(SELECT 1 FROM seat_forecast f JOIN vehicle_observation o ON o.id=f.vehicle_observation_id
            JOIN observation_batch b ON b.id=o.observation_batch_id
            LEFT JOIN forecast_evaluation_result e USING(vehicle_observation_id,target_stop_order)
            LEFT JOIN forecast_evaluation_compact c USING(vehicle_observation_id,target_stop_order)
            WHERE f.route_version_id=:v AND COALESCE(b.response_received_at,b.scheduled_at)>=:from
              AND COALESCE(b.response_received_at,b.scheduled_at)<:until
              AND (f.generated_at>=:cutoff OR e.scored_at>=:cutoff OR e.arrived_at>=:cutoff
                OR c.scored_at>=:cutoff OR c.arrived_at>=:cutoff))
        OR EXISTS(SELECT 1 FROM alighting_forecast_evaluation_metric e
            JOIN vehicle_observation o ON o.id=e.vehicle_observation_id
            JOIN observation_batch b ON b.id=o.observation_batch_id
            WHERE e.route_version_id=:v AND COALESCE(b.response_received_at,b.scheduled_at)>=:from
                AND COALESCE(b.response_received_at,b.scheduled_at)<:until
                AND (e.scored_at>=:cutoff OR e.event_at>=:cutoff))
        """;

    OperationalArchiveIntegrationTest fixture;
    JdbcClient jdbc;
    long model;
    int routes;

    @BeforeAll
    void migrate() {
        fixture = new OperationalArchiveIntegrationTest();
        fixture.migrate();
        jdbc = fixture.jdbc;
    }

    @AfterAll
    void close() {
        fixture.close();
    }

    @BeforeEach
    void setup() {
        jdbc.sql("TRUNCATE route,model_deployment CASCADE").update();
        model = jdbc.sql("""
            INSERT INTO model_deployment(deployment_key,release_id,model_key,model_version,bundle_digest,
                prediction_target_version,calculation_version,supported_scope_digest,data_until,state)
            VALUES(gen_random_uuid(),'test','seat','1',?,'target','calc',?,'2026-01-01','STAGED') RETURNING id
            """).params("1".repeat(64), "2".repeat(64)).query(Long.class).single();
    }

    @Test
    void 보관_기준_뒤의_예보와_정산_기록이_있으면_이전_질의와_같이_이관을_막는다() {
        // given
        Map<String, Long> versions = new LinkedHashMap<>();
        versions.put("모든 기록이 기준 전", settled(OLD, OLD, OLD));
        versions.put("정산 없는 최근 예보", forecastOnly(RECENT));
        versions.put("최근 정산 시각", settled(OLD, RECENT, null));
        versions.put("최근 도착 시각", settled(OLD, OLD, RECENT));
        versions.put("도착 시각 없는 과거 정산", settled(OLD, OLD, null));
        versions.put("최근 축약 정산 시각", compact(RECENT, OLD));
        versions.put("최근 축약 도착 시각", compact(OLD, RECENT));
        versions.put("과거 축약 정산", compact(OLD, OLD));

        // when
        Map<String, String> readiness = readiness(versions);

        // then
        assertThat(readiness).containsExactly(
            Map.entry("모든 기록이 기준 전", "READY"),
            Map.entry("정산 없는 최근 예보", "RECENT_CHILD_RECORD"),
            Map.entry("최근 정산 시각", "RECENT_CHILD_RECORD"),
            Map.entry("최근 도착 시각", "RECENT_CHILD_RECORD"),
            Map.entry("도착 시각 없는 과거 정산", "READY"),
            Map.entry("최근 축약 정산 시각", "RECENT_CHILD_RECORD"),
            Map.entry("최근 축약 도착 시각", "RECENT_CHILD_RECORD"),
            Map.entry("과거 축약 정산", "READY"));
        assertSamePrevious(versions, readiness);
    }

    @Test
    void 다른_노선과_다른_날짜의_최근_기록은_판정에_섞이지_않는다() {
        // given
        final long quiet = version();
        forecast(observation(quiet, batch(quiet, OLD, OLD)), OLD);
        forecast(observation(quiet, batch(quiet, "2026-11-08T03:00Z", "2026-11-08T03:00Z")), RECENT);
        forecast(observation(quiet, batch(quiet, "2026-11-06T03:00Z", "2026-11-06T03:00Z")), RECENT);
        final long busy = version();
        forecast(observation(busy, batch(busy, OLD, OLD)), RECENT);
        Map<String, Long> versions = new LinkedHashMap<>();
        versions.put("앞뒤 날짜에만 최근 기록", quiet);
        versions.put("같은 날짜의 다른 노선", busy);

        // when
        Map<String, String> readiness = readiness(versions);

        // then
        assertThat(readiness).containsExactly(
            Map.entry("앞뒤 날짜에만 최근 기록", "READY"),
            Map.entry("같은 날짜의 다른 노선", "RECENT_CHILD_RECORD"));
        assertSamePrevious(versions, readiness);
    }

    @Test
    void 응답_시각이_없거나_날짜_경계에_걸친_배치는_이전_질의와_같은_날짜로_판정한다() {
        // given
        Map<String, Long> versions = new LinkedHashMap<>();
        versions.put("응답 없는 배치의 예약 시각이 그날", recentOn(OLD, null));
        versions.put("응답 없는 배치의 예약 시각이 다음날", recentOn("2026-11-08T03:00Z", null));
        versions.put("예약은 그날, 응답은 다음날 0시", recentOn("2026-11-07T14:59Z", "2026-11-07T15:00:01Z"));
        versions.put("예약은 전날, 응답은 그날 0시 정각", recentOn("2026-11-06T14:59Z", "2026-11-06T15:00Z"));
        versions.put("응답이 다음날 0시 정각", recentOn("2026-11-07T14:00Z", "2026-11-07T15:00Z"));
        versions.put("응답이 그날 마지막 순간", recentOn("2026-11-07T14:00Z", "2026-11-07T14:59:59.999999Z"));

        // when
        Map<String, String> readiness = readiness(versions);

        // then
        assertThat(readiness).containsExactly(
            Map.entry("응답 없는 배치의 예약 시각이 그날", "RECENT_CHILD_RECORD"),
            Map.entry("응답 없는 배치의 예약 시각이 다음날", "READY"),
            Map.entry("예약은 그날, 응답은 다음날 0시", "READY"),
            Map.entry("예약은 전날, 응답은 그날 0시 정각", "RECENT_CHILD_RECORD"),
            Map.entry("응답이 다음날 0시 정각", "READY"),
            Map.entry("응답이 그날 마지막 순간", "RECENT_CHILD_RECORD"));
        assertSamePrevious(versions, readiness);
    }

    @Test
    void 승하차_반영_정산은_삭제한_보관_기록까지_이전_질의와_같이_판정한다() {
        // given
        Map<String, Long> versions = new LinkedHashMap<>();
        versions.put("과거 결과", alighting("result", OLD, OLD));
        versions.put("최근 결과 정산 시각", alighting("result", RECENT, OLD));
        versions.put("최근 결과 사건 시각", alighting("result", OLD, RECENT));
        versions.put("사건 시각 없는 과거 결과", alighting("result", OLD, null));
        versions.put("원본 삭제한 보관의 최근 정산", alighting("PURGED", RECENT, OLD));
        versions.put("원본 삭제한 보관의 최근 사건", alighting("PURGED", OLD, RECENT));
        versions.put("원본이 남은 보관의 최근 정산", alighting("LIVE", RECENT, RECENT));

        // when
        Map<String, String> readiness = readiness(versions);

        // then
        assertThat(readiness).containsExactly(
            Map.entry("과거 결과", "READY"),
            Map.entry("최근 결과 정산 시각", "RECENT_CHILD_RECORD"),
            Map.entry("최근 결과 사건 시각", "RECENT_CHILD_RECORD"),
            Map.entry("사건 시각 없는 과거 결과", "READY"),
            Map.entry("원본 삭제한 보관의 최근 정산", "RECENT_CHILD_RECORD"),
            Map.entry("원본 삭제한 보관의 최근 사건", "RECENT_CHILD_RECORD"),
            Map.entry("원본이 남은 보관의 최근 정산", "READY"));
        assertSamePrevious(versions, readiness);
    }

    private Map<String, String> readiness(Map<String, Long> versions) {
        Map<String, String> result = new LinkedHashMap<>();
        versions.forEach((name, version) -> result.put(name, fixture.repository.readiness(job(version))));
        return result;
    }

    private void assertSamePrevious(Map<String, Long> versions, Map<String, String> readiness) {
        assertSoftly(softly -> versions.forEach((name, version) -> softly.assertThat(readiness.get(name))
            .as(name).isEqualTo(previous(version) ? "RECENT_CHILD_RECORD" : "READY")));
    }

    private boolean previous(final long version) {
        return jdbc.sql(PREVIOUS_RECENT).param("v", version)
            .param("from", ArchiveRetention.start(DATE).atOffset(ZoneOffset.UTC))
            .param("until", ArchiveRetention.start(DATE.plusDays(1)).atOffset(ZoneOffset.UTC))
            .param("cutoff", CUTOFF.atOffset(ZoneOffset.UTC)).query(Boolean.class).single();
    }

    private ArchiveRepository.Job job(final long version) {
        UUID id = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO operational_archive_job(id,route_version_id,source_date,cutoff,state,lease_token,lease_until)
            VALUES(?,?,?,?,'EXPORTING',?,clock_timestamp()+interval '5 minutes')
            """).params(id, version, DATE, CUTOFF.atOffset(ZoneOffset.UTC), token).update();
        return new ArchiveRepository.Job(id, version, DATE, CUTOFF, token, "EXPORTING");
    }

    private long version() {
        routes++;
        final long route = jdbc.sql("""
            INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)
            VALUES(?,'GBIS',?,?,'A','B') RETURNING id
            """).params("r" + routes, "r" + routes, "route-" + routes).query(Long.class).single();
        final long version = jdbc.sql("""
            INSERT INTO route_version(route_id,content_digest,valid_from) VALUES(?,?,'2026-01-01') RETURNING id
            """).params(route, "0".repeat(64)).query(Long.class).single();
        jdbc.sql("""
            INSERT INTO route_stop(route_version_id,stop_order,stop_id,name,direction,boarding_allowed)
            VALUES(?,1,'s1','S1','UP',true),(?,2,'s2','S2','UP',true)
            """).params(version, version).update();
        return version;
    }

    private long batch(final long version, String scheduled, String response) {
        return jdbc.sql("""
            INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,requested_at,
                response_received_at,completed_at,outcome,normalization_version,collection_strategy_version)
            VALUES(?,CAST(? AS timestamptz),1,?,CAST(? AS timestamptz),CAST(? AS timestamptz),CAST(? AS timestamptz),
                CASE WHEN ? THEN 'SUCCESS_ROWS' ELSE 'FAILED_UNREADABLE' END,'n','c') RETURNING id
            """).params(version, scheduled, UUID.randomUUID().toString(), scheduled, response, scheduled,
            response != null).query(Long.class).single();
    }

    private long observation(final long version, final long batch) {
        return jdbc.sql("""
            INSERT INTO vehicle_observation(observation_batch_id,route_version_id,source_row_number,vehicle_id,
                stop_order,stop_id,running_state,passed_stop_order,remaining_seats)
            VALUES(?,?,1,'vehicle',1,'s1',2,1,12) RETURNING id
            """).params(batch, version).query(Long.class).single();
    }

    private long forecast(final long observation, String generatedAt) {
        jdbc.sql("""
            INSERT INTO forecast_publication(source_batch_id,route_version_id,model_deployment_id,
                demand_statistics_revision,quality_revision,observed_at,generated_at,published_at,prediction_count)
            SELECT o.observation_batch_id,o.route_version_id,?,1,1,b.scheduled_at,b.scheduled_at,b.scheduled_at,1
            FROM vehicle_observation o JOIN observation_batch b ON b.id=o.observation_batch_id WHERE o.id=?
            """).params(model, observation).update();
        jdbc.sql("""
            INSERT INTO seat_forecast(publication_id,vehicle_observation_id,target_stop_order,route_version_id,
                stops_to_target,model_deployment_id,demand_statistics_revision,seat_full_chance_raw,seat_full_chance,
                generated_at,quality_revision)
            SELECT p.id,o.id,2,o.route_version_id,1,?,1,0.2,0.3,CAST(? AS timestamptz),1
            FROM vehicle_observation o JOIN forecast_publication p ON p.source_batch_id=o.observation_batch_id
            WHERE o.id=?
            """).params(model, generatedAt, observation).update();
        return observation;
    }

    private long forecastOnly(String generatedAt) {
        final long version = version();
        forecast(observation(version, batch(version, OLD, OLD)), generatedAt);
        return version;
    }

    private long recentOn(String scheduled, String response) {
        final long version = version();
        forecast(observation(version, batch(version, scheduled, response)), RECENT);
        return version;
    }

    private long settled(String generatedAt, String scoredAt, String arrivedAt) {
        final long version = version();
        final long source = forecast(observation(version, batch(version, OLD, OLD)), generatedAt);
        if (arrivedAt == null) {
            jdbc.sql("""
                INSERT INTO forecast_evaluation_result(vehicle_observation_id,target_stop_order,route_version_id,
                    scoring_state,scored_at) VALUES(?,2,?,'SKIPPED',CAST(? AS timestamptz))
                """).params(source, version, scoredAt).update();
            return version;
        }
        final long arrival = observation(version, batch(version, "2026-11-07T03:10Z", "2026-11-07T03:10Z"));
        jdbc.sql("""
            INSERT INTO forecast_evaluation_result(vehicle_observation_id,target_stop_order,route_version_id,
                scoring_state,arrival_observation_id,scored_at,arrived_at,arrival_route_version_id,arrival_stop_order,
                arrival_running_state,arrival_seat_unknown_reason,arrival_quality_direction)
            VALUES(?,2,?,'SEAT_MISSING',?,CAST(? AS timestamptz),CAST(? AS timestamptz),?,2,2,'UNKNOWN',0)
            """).params(source, version, arrival, scoredAt, arrivedAt, version).update();
        return version;
    }

    private long compact(String scoredAt, String arrivedAt) {
        final long version = version();
        final long source = forecast(observation(version, batch(version, OLD, OLD)), OLD);
        final long arrival = observation(version, batch(version, "2026-11-07T03:10Z", "2026-11-07T03:10Z"));
        jdbc.sql("""
            INSERT INTO forecast_evaluation_compact(vehicle_observation_id,target_stop_order,arrival_observation_id,
                seats_on_arrival,scored_at,arrived_at,arrival_quality_direction)
            VALUES(?,2,?,9,CAST(? AS timestamptz),CAST(? AS timestamptz),0)
            """).params(source, arrival, scoredAt, arrivedAt).update();
        return version;
    }

    private long alighting(String storage, String scoredAt, String eventAt) {
        final long version = version();
        final long source = forecast(observation(version, batch(version, OLD, OLD)), OLD);
        if (storage.equals("result")) {
            jdbc.sql("""
                INSERT INTO alighting_forecast_evaluation_result(vehicle_observation_id,target_stop_order,target_kind,
                    label_version,route_version_id,scoring_state,label_value,pre_observation_id,quality_revision,
                    scored_at,event_at)
                VALUES(?,2,'AFTER_ALIGHTING_ESTIMATE','label-v1',?,'SETTLED',12.5,?,1,CAST(? AS timestamptz),
                    CAST(? AS timestamptz))
                """).params(source, version, source, scoredAt, eventAt).update();
            return version;
        }
        jdbc.sql("""
            INSERT INTO alighting_evaluation_archive(vehicle_observation_id,target_stop_order,target_kind,label_version,
                quality_revision,route_version_id,label_value,scoring_state,event_at,scored_at,object_hash,storage_state)
            VALUES(?,2,'AFTER_ALIGHTING_ESTIMATE','label-v1',1,?,12.5,'SETTLED',CAST(? AS timestamptz),
                CAST(? AS timestamptz),repeat('a',64),?)
            """).params(source, version, eventAt, scoredAt, storage).update();
        return version;
    }
}
