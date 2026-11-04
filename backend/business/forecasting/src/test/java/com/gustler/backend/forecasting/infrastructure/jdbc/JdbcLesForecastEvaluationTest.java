package com.gustler.backend.forecasting.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.forecasting.api.evaluation.EvaluationArchiveObjects;
import com.gustler.backend.forecasting.application.evaluation.LesEvaluationArchiveService;
import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import com.gustler.backend.support.ConfirmedTripFixture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ForecastingIntegrationTest
class JdbcLesForecastEvaluationTest {
    private static final Instant START = Instant.parse("2026-01-05T00:00:00Z");
    private static final String VERSION = "les-identity-safe-v1";
    private static final String AFTER = "AFTER_ALIGHTING_ESTIMATE";
    @Autowired
    JdbcClient jdbc;
    @Autowired
    RouteDataQualityAccess quality;
    @Autowired
    PlatformTransactionManager transactions;
    @Autowired
    JdbcEvaluationWorkStore legacyWork;
    private TransactionTemplate tx;
    private long route;
    private long version;
    private long deployment;
    private long source;
    private long post;
    private JdbcLesForecastEvaluation service;
    private final String snapshot = """
        {"realStopOrders":[1,2,3],"alighting":{"stops":[{"stop":2,"band":"morning",
        "p":0.12,"lam":[1.5,1.5,1.5,1.5,1.5,1.5,1.5,1.5],"kappa":1,"r":2}]}}
        """;

    @BeforeEach
    void 정산_자료를_준비한다() {
        tx = new TransactionTemplate(transactions);
        service = new JdbcLesForecastEvaluation(jdbc, quality, Clock.systemUTC(), transactions);
        tx.executeWithoutResult(status -> {
            String id = UUID.randomUUID().toString().substring(0, 24);
            route = jdbc.sql("""
                INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)
                VALUES(:id,'GBIS',:id,'3330','A','B') RETURNING id
                """).param("id", id).query(Long.class).single();
            version = jdbc
                .sql("INSERT INTO route_version(route_id,content_digest,valid_from) VALUES(?,?,?) RETURNING id")
                .params(route, "0".repeat(64), offset(START)).query(Long.class).single();
            for (int stop = 1; stop <= 3; stop++) {
                jdbc.sql(
                    "INSERT INTO route_stop(route_version_id,stop_order,stop_id,name,direction,boarding_allowed) VALUES(?,?,?,?,?,true)")
                    .params(version, stop, "s" + stop, "정류장" + stop, "UP").update();
            }
            quality.ensureExists(version);
            deployment = jdbc.sql("""
                INSERT INTO model_deployment(deployment_key,release_id,model_key,model_version,bundle_digest,
                    prediction_target_version,calculation_version,supported_scope_digest,data_until,state)
                VALUES(?,'les-test','les','1',?,'LES_V1','LES_V1',?,?,'ACTIVE') RETURNING id
                """).params(UUID.randomUUID(), "0".repeat(64), "0".repeat(64), offset(START)).query(Long.class)
                .single();
            observation("previous", -120, 1, 5);
            observation("previous", -100, 2, 0);
            observation("previous", -80, 3, 0);
            source = observation("current", 0, 1, 40);
            observation("current", 20, 1, 2);
            observation("current", 40, 2, 0);
            post = observation("current", 60, 2, 0);
            observation("current", 80, 3, 2);
            final long publication = jdbc
                .sql(
                    """
                        INSERT INTO forecast_publication(source_batch_id,route_version_id,model_deployment_id,
                            demand_statistics_revision,quality_revision,observed_at,generated_at,published_at,prediction_count)
                        SELECT observation_batch_id,route_version_id,?,0,1,?,?,?,1 FROM vehicle_observation WHERE id=? RETURNING id
                        """)
                .params(deployment, offset(START), offset(START.plusSeconds(1)), offset(START.plusSeconds(2)), source)
                .query(Long.class).single();
            jdbc.sql(
                """
                    INSERT INTO seat_forecast(vehicle_observation_id,target_stop_order,route_version_id,stops_to_target,
                        model_deployment_id,demand_statistics_revision,seat_full_chance_raw,seat_full_chance,expected_seats,
                        generated_at,quality_revision,seat_target,probability_target,label_version,feature_snapshot,inference_bundle_hash,publication_id)
                    VALUES(?,2,?,1,?,0,.8,.8,3,?,1,'AFTER_ALIGHTING_ESTIMATE','DEPARTURE_ZERO',?,CAST(? AS jsonb),?,?)
                    """)
                .params(source, version, deployment, offset(START.plusSeconds(1)), VERSION, snapshot, "0".repeat(64),
                    publication)
                .update();
        });
    }

    @AfterEach
    void 준비한_자료를_정리한다() {
        tx.executeWithoutResult(status -> {
            for (String table : List.of("les_evaluation_archive", "les_forecast_evaluation_result",
                "les_forecast_evaluation_work",
                "forecast_evaluation_work", "seat_forecast", "forecast_publication", "trip_quality_rebuild")) {
                jdbc.sql("DELETE FROM " + table + " WHERE route_version_id=?").param(version).update();
            }
            jdbc.sql(
                "DELETE FROM observation_trip_assignment WHERE observation_id IN (SELECT id FROM vehicle_observation WHERE route_version_id=?)")
                .param(version).update();
            jdbc.sql("DELETE FROM vehicle_one_way_trip WHERE route_version_id=?").param(version).update();
            jdbc.sql("DELETE FROM vehicle_observation WHERE route_version_id=?").param(version).update();
            jdbc.sql("DELETE FROM observation_batch WHERE route_version_id=?").param(version).update();
            jdbc.sql("DELETE FROM route_stop WHERE route_version_id=?").param(version).update();
            jdbc.sql("DELETE FROM route_data_quality WHERE route_id=?").param(route).update();
            jdbc.sql("DELETE FROM route_version WHERE id=?").param(version).update();
            jdbc.sql("DELETE FROM route WHERE id=?").param(route).update();
            jdbc.sql("DELETE FROM model_deployment WHERE id=?").param(deployment).update();
        });
    }

    @Test
    void 발행시_두_목표를_등록하고_기존_정산에_넣지_않는다() {
        assertThat(count("les_forecast_evaluation_work")).isEqualTo(2);
        tx.executeWithoutResult(status -> legacyWork.register(version, List.of(source)));
        assertThat(count("forecast_evaluation_work")).isZero();
        assertThat(count("forecast_evaluation_pending")).isZero();
    }

    @Test
    void 같은_편도의_소수_라벨과_출발_만석을_독립적으로_한번만_정산한다() {
        service.settle();
        service.settle();
        assertThat(count("les_forecast_evaluation_result")).isEqualTo(2);
        assertThat(count("les_forecast_evaluation_work")).isZero();
        assertThat(label(AFTER)).isBetween(2.0, 40.0).isNotEqualTo(Math.rint(label(AFTER)));
        assertThat(label("DEPARTURE_ZERO")).isEqualTo(1);
        assertThat(count("forecast_evaluation_result")).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM stop_demand_pending_sample WHERE prediction_observation_id=?")
            .param(source).query(Integer.class).single()).isZero();
    }

    @Test
    void 늦은_계산시각과_관측시각을_구별해_재현한다() {
        jdbc.sql("UPDATE seat_forecast SET generated_at=? WHERE vehicle_observation_id=?")
            .params(offset(START.plusSeconds(172800)), source).update();
        service.settle();
        assertThat(label("DEPARTURE_ZERO")).isEqualTo(1);
        assertThat(label(AFTER)).isGreaterThan(2);
    }

    @Test
    void 용량_근거가_없어도_출발_만석_평가는_완료한다() {
        jdbc.sql("UPDATE vehicle_observation SET remaining_seats=20 WHERE id=?").param(source).update();
        service.settle();
        assertThat(label("DEPARTURE_ZERO")).isEqualTo(1);
        assertThat(jdbc
            .sql("SELECT reason FROM les_forecast_evaluation_result WHERE vehicle_observation_id=? AND target_kind=?")
            .params(source, AFTER).query(String.class).single()).isEqualTo("CAPACITY_NOT_OBSERVED");
    }

    @Test
    void 다른_편도_관측을_목표_결과로_연결하지_않는다() {
        jdbc.sql("DELETE FROM observation_trip_assignment WHERE observation_id=?").param(post).update();
        service.settle();
        assertThat(jdbc.sql(
            "SELECT count(*) FROM les_forecast_evaluation_result WHERE route_version_id=? AND scoring_state='SETTLED'")
            .param(version).query(Integer.class).single()).isZero();
        assertThat(count("les_forecast_evaluation_result")).isEqualTo(2);
    }

    @Test
    void 품질_재평가는_이전_숫자를_지우지_않고_새_판본으로_남긴다() {
        service.settle();
        jdbc.sql("UPDATE route_data_quality SET quality_revision=quality_revision+1 WHERE route_id=?").param(route)
            .update();
        service.settle();
        assertThat(count("les_forecast_evaluation_result")).isEqualTo(4);
        assertThat(count("les_quality_eligible_metric")).isEqualTo(2);
    }

    @Test
    void 이관_후_원자료가_삭제돼도_같은_라벨을_복원하며_집계가_유지된다() {
        service.settle();
        final double before = label(AFTER);
        jdbc.sql("UPDATE les_forecast_evaluation_result SET scored_at=? WHERE route_version_id=?")
            .params(offset(START), version).update();
        MemoryObjects objects = new MemoryObjects();
        LesEvaluationArchiveService archive = new LesEvaluationArchiveService(
            new JdbcLesEvaluationArchiveStore(jdbc, transactions), objects, true, 500);
        archive.advance();
        assertThat(objects.bytes).hasSize(1);
        assertThat(count("les_forecast_evaluation_result")).isZero();
        assertThat(count("les_forecast_evaluation_metric")).isEqualTo(2);
        jdbc.sql("DELETE FROM observation_trip_assignment WHERE observation_id=?").param(post).update();
        jdbc.sql("DELETE FROM vehicle_observation WHERE id=?").param(post).update();
        assertThat(archive.restore(source, 2, AFTER, VERSION, 1)).isEqualTo(1);
        assertThat(archive.restore(source, 2, AFTER, VERSION, 1)).isZero();
        assertThat(label(AFTER)).isEqualTo(before);
        assertThat(count("les_forecast_evaluation_metric")).isEqualTo(2);
        assertThat(count("les_forecast_evaluation_work")).isZero();
        assertThat(
            jdbc.sql("SELECT evidence_archive_hash FROM les_forecast_evaluation_result WHERE vehicle_observation_id=?")
                .param(source).query(String.class).single())
            .hasSize(64);
    }

    @Test
    void 원격_검증에_실패하면_Les_원본을_삭제하지_않는다() {
        service.settle();
        EvaluationArchiveObjects corrupted = new EvaluationArchiveObjects() {
            public void putIfAbsent(
                String bucket,
                String key,
                Path source,
                String checksum) {
            }

            public void download(
                String bucket,
                String key,
                Path destination,
                final long limit) {
                try {
                    Files.writeString(destination, "corrupted");
                } catch (IOException error) {
                    throw new IllegalStateException(error);
                }
            }
        };
        jdbc.sql("UPDATE les_forecast_evaluation_result SET scored_at=? WHERE route_version_id=?")
            .params(offset(START), version).update();
        LesEvaluationArchiveService archive = new LesEvaluationArchiveService(
            new JdbcLesEvaluationArchiveStore(jdbc, transactions), corrupted, true, 1);
        assertThatThrownBy(archive::advance).isInstanceOf(IllegalStateException.class);
        assertThat(count("les_forecast_evaluation_result")).isEqualTo(2);
        assertThat(count("les_evaluation_archive")).isZero();
    }

    @Test
    void 삭제설정을_나중에_켜도_기존_검증_묶음으로_삭제한다() {
        service.settle();
        jdbc.sql("UPDATE les_forecast_evaluation_result SET scored_at=? WHERE route_version_id=?")
            .params(offset(START), version).update();
        MemoryObjects objects = new MemoryObjects();
        new LesEvaluationArchiveService(new JdbcLesEvaluationArchiveStore(jdbc, transactions), objects, false, 500)
            .advance();
        assertThat(count("les_forecast_evaluation_result")).isEqualTo(2);
        new LesEvaluationArchiveService(new JdbcLesEvaluationArchiveStore(jdbc, transactions), objects, true, 1)
            .advance();
        assertThat(count("les_forecast_evaluation_result")).isZero();
        assertThat(objects.bytes).hasSize(1);
    }

    @Test
    void 선행차량이_나중에_확정되면_라벨의_확정시각도_늦춘다() {
        tx.executeWithoutResult(status -> {
            jdbc.sql(
                "UPDATE vehicle_observation SET passed_stop_order=2,stop_order=2,stop_id='s2' WHERE route_version_id=? AND vehicle_id='previous' AND passed_stop_order=3")
                .param(version).update();
            observation("previous", 0, 2, 0);
            observation("previous", 60, 2, 0);
            observation("previous", 120, 2, 0);
            observation("previous", 180, 3, 0);
        });
        service.settle();
        assertThat(jdbc
            .sql("SELECT known_at FROM les_forecast_evaluation_result WHERE vehicle_observation_id=? AND target_kind=?")
            .params(source, AFTER).query(OffsetDateTime.class).single().toInstant()).isEqualTo(START.plusSeconds(180));
    }

    @Test
    void 품질_조사가_끝날_때까지_오래된_예보도_실패로_확정하지_않는다() {
        jdbc.sql("INSERT INTO trip_quality_rebuild(route_version_id,vehicle_id,until_at) VALUES(?,'',?)")
            .params(version, offset(START.plusSeconds(90))).update();
        service.settle();
        assertThat(count("les_forecast_evaluation_result")).isZero();
        assertThat(count("les_forecast_evaluation_work")).isEqualTo(2);
        assertThat(jdbc.sql("SELECT DISTINCT reason FROM les_evaluation_status WHERE route_version_id=?")
            .param(version).query(String.class).list()).containsExactly("QUALITY_REBUILD_PENDING");
        jdbc.sql("UPDATE trip_quality_rebuild SET completed=true,phase='DONE' WHERE route_version_id=?").param(version)
            .update();
        jdbc.sql("UPDATE les_forecast_evaluation_work SET next_attempt_at=? WHERE route_version_id=?")
            .params(offset(START), version).update();
        service.settle();
        assertThat(label("DEPARTURE_ZERO")).isEqualTo(1);
    }

    @Test
    void 조회_상한_초과는_관측실패가_아닌_지원불가로_기록하고_설정한도를_적용한다() {
        new JdbcLesForecastEvaluation(jdbc, quality, Clock.systemUTC(), transactions, 7).settle();
        assertThat(
            jdbc.sql("SELECT DISTINCT scoring_state||':'||reason FROM les_evaluation_status WHERE route_version_id=?")
                .param(version).query(String.class).list())
            .containsExactly("UNSUPPORTED:OBSERVATION_INPUT_LIMIT");
        assertThat(count("les_forecast_evaluation_work")).isZero();
        jdbc.sql("UPDATE route_data_quality SET quality_revision=quality_revision+1 WHERE route_id=?").param(route)
            .update();
        new JdbcLesForecastEvaluation(jdbc, quality, Clock.systemUTC(), transactions, 8).settle();
        assertThat(jdbc
            .sql(
                "SELECT count(*) FROM les_quality_eligible_metric WHERE route_version_id=? AND scoring_state='SETTLED'")
            .param(version).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void 원자료_부분삭제는_지원불가로_구별하고_보관뒤에도_이유와_상태를_조회한다() {
        jdbc.sql("DELETE FROM observation_trip_assignment WHERE observation_id=?").param(post).update();
        jdbc.sql("DELETE FROM vehicle_observation WHERE id=?").param(post).update();
        service.settle();
        assertThat(
            jdbc.sql("SELECT DISTINCT scoring_state||':'||reason FROM les_evaluation_status WHERE route_version_id=?")
                .param(version).query(String.class).list())
            .containsExactly("UNSUPPORTED:SOURCE_HISTORY_INCOMPLETE");
        jdbc.sql("UPDATE les_forecast_evaluation_result SET scored_at=? WHERE route_version_id=?")
            .params(offset(START), version).update();
        new LesEvaluationArchiveService(new JdbcLesEvaluationArchiveStore(jdbc, transactions), new MemoryObjects(),
            true, 500).advance();
        assertThat(
            jdbc.sql("SELECT DISTINCT reason||':'||retention_state FROM les_evaluation_status WHERE route_version_id=?")
                .param(version).query(String.class).list())
            .containsExactly("SOURCE_HISTORY_INCOMPLETE:ARCHIVED");
    }

    @Test
    void 수집건수_기록이_없으면_부분삭제라고_단정하지_않고_검증불가로_구분한다() {
        jdbc.sql(
            "UPDATE observation_batch SET stored_rows=NULL WHERE id=(SELECT observation_batch_id FROM vehicle_observation WHERE id=?)")
            .param(source).update();
        service.settle();
        assertThat(jdbc.sql("SELECT DISTINCT reason FROM les_evaluation_status WHERE route_version_id=?")
            .param(version).query(String.class).list()).containsExactly("SOURCE_HISTORY_UNVERIFIED");
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void 품질판본이_그대로여도_조사중인_성적은_보관여부와_무관하게_제외한다(final boolean archived) {
        service.settle();
        if (archived) {
            jdbc.sql("UPDATE les_forecast_evaluation_result SET scored_at=? WHERE route_version_id=?")
                .params(offset(START), version).update();
            new LesEvaluationArchiveService(new JdbcLesEvaluationArchiveStore(jdbc, transactions), new MemoryObjects(),
                true, 500).advance();
        }
        assertThat(count("les_quality_eligible_metric")).isEqualTo(2);
        jdbc.sql("INSERT INTO trip_quality_rebuild(route_version_id,vehicle_id,until_at) VALUES(?,'',?)")
            .params(version, offset(START.plusSeconds(90))).update();
        assertThat(count("les_forecast_evaluation_metric")).isEqualTo(2);
        assertThat(count("les_quality_eligible_metric")).isZero();
        assertThat(count("les_quality_eligible_evaluation")).isZero();
        jdbc.sql("UPDATE trip_quality_rebuild SET completed=true,phase='DONE' WHERE route_version_id=?").param(version)
            .update();
        assertThat(count("les_quality_eligible_metric")).isEqualTo(2);
        if (archived) {
            assertThat(count("les_quality_eligible_evaluation")).isZero();
            return;
        }
        assertThat(count("les_quality_eligible_evaluation")).isEqualTo(2);
    }

    private long observation(
        String vehicle,
        final int seconds,
        final int stop,
        final int seats) {
        OffsetDateTime time = offset(START.plusSeconds(seconds));
        final long batch = jdbc
            .sql(
                """
                    INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,requested_at,response_received_at,
                        outcome,normalization_version,collection_strategy_version,stored_rows)
                    VALUES(?,?,1,?,?,?,'SUCCESS_ROWS','normalization-v1.0.0','adaptive-kst-v1.0.1',1) RETURNING id
                    """)
            .params(version, time, UUID.randomUUID().toString(), time, time).query(Long.class).single();
        final long id = jdbc
            .sql(
                """
                    INSERT INTO vehicle_observation(observation_batch_id,route_version_id,source_row_number,vehicle_id,stop_order,stop_id,
                        passed_stop_order,running_state,remaining_seats) VALUES(?,?,0,?,?,?,?,2,?) RETURNING id
                    """)
            .params(batch, version, vehicle, stop, "s" + stop, stop, seats).query(Long.class).single();
        return ConfirmedTripFixture.include(jdbc, id);
    }

    private int count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE route_version_id=?").param(version)
            .query(Integer.class).single();
    }

    private double label(String target) {
        return jdbc.sql(
            "SELECT label_value FROM les_forecast_evaluation_result WHERE vehicle_observation_id=? AND target_kind=?")
            .params(source, target).query(Double.class).single();
    }

    private static OffsetDateTime offset(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private static final class MemoryObjects implements EvaluationArchiveObjects {
        private final Map<String, byte[]> bytes = new HashMap<>();

        public void putIfAbsent(
            String bucket,
            String key,
            Path source,
            String checksum) {
            try {
                bytes.putIfAbsent(key, Files.readAllBytes(source));
            } catch (IOException error) {
                throw new IllegalStateException(error);
            }
        }

        public void download(
            String bucket,
            String key,
            Path destination,
            final long limit) {
            try {
                Files.write(destination, bytes.get(key), StandardOpenOption.CREATE_NEW);
            } catch (IOException error) {
                throw new IllegalStateException(error);
            }
        }
    }
}
