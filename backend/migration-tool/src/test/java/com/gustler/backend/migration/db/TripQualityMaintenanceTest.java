package com.gustler.backend.migration.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.migration.quality.TripQualityMaintenance;
import com.gustler.backend.observation.VehicleObservationsStored;
import com.gustler.backend.processor.TripQualityRepository;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class TripQualityMaintenanceTest extends PostgresMigrationTestSupport {
    private static final Instant START = Instant.parse("2026-09-21T00:00:00Z");

    @BeforeEach
    void 이전_테스트가_커밋한_자료를_비운다() throws Exception {
        try (var c = connection(); var s = c.createStatement()) { s.execute("TRUNCATE route CASCADE"); }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 30})
    void 정상_관측은_차량이_1대든_30대든_추가_SQL과_판정_저장이_없다(int vehicles) throws Exception {
        // given
        try (var c = connection()) {
            var jdbc = jdbc(c); long version = route(jdbc); long batch = batch(jdbc, version, 1, 1, vehicles, 44);
            var event = event(jdbc, version, batch);
            var calls = new AtomicInteger(); var counted = counted(c, calls);

            // when
            new TripQualityRepository(counted).observationsStored(event);

            // then
            assertThat(calls.get()).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM trip_quality_rebuild").query(Integer.class).single()).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM vehicle_one_way_trip").query(Integer.class).single()).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM vehicle_observation WHERE vehicle_trip_key IS NOT NULL").query(Integer.class).single()).isZero();
        }
    }

    @Test
    void 이상_관측의_저장과_조사_요청은_함께_롤백된다() throws Exception {
        // given
        try (var c = connection()) {
            var jdbc = jdbc(c); long version = route(jdbc);
            c.setAutoCommit(false);
            long batch = batch(jdbc, version, 1, 1, 1, 71);
            new TripQualityRepository(jdbc).observationsStored(event(jdbc, version, batch));
            assertThat(jdbc.sql("SELECT count(*) FROM trip_quality_rebuild").query(Integer.class).single()).isEqualTo(1);

            // when
            c.rollback();

            // then
            assertThat(jdbc.sql("SELECT count(*) FROM vehicle_observation").query(Integer.class).single()).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM trip_quality_rebuild").query(Integer.class).single()).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 조사_중에는_문제_차량만_보류하고_완료하면_문제_편도를_제외한_다음_편도를_사용한다(boolean configured) throws Exception {
        // given
        try (var c = connection()) {
            var jdbc = jdbc(c); long version = route(jdbc);
            if (configured) { jdbc.sql("UPDATE route_version SET maximum_observation_gap_seconds=60, observation_gap_evidence='synthetic' WHERE id=?").param(version).update(); }
            batch(jdbc, version, 1, 1, 2, 44);
            long bad = batch(jdbc, version, 2, 2, 2, 71);
            long next = batch(jdbc, version, 3, 4, 2, 44);
            var quality = new TripQualityRepository(jdbc);
            c.setAutoCommit(false);

            // when
            quality.observationsStored(event(jdbc, version, bad));
            c.commit();

            // then: bus-1만 보류하고 정상 bus-2는 유지한다.
            assertThat(jdbc.sql("SELECT maximum_gap_seconds FROM trip_quality_rebuild")
                .query(Integer.class).single()).isEqualTo(configured ? 60 : 600);
            assertThat(jdbc.sql("SELECT DISTINCT vehicle_id FROM forecast_eligible_observation").query(String.class).list()).containsExactly("bus-2");
            assertThat(jdbc.sql("SELECT count(*) FROM vehicle_observation WHERE vehicle_trip_key IS NOT NULL").query(Integer.class).single()).isZero();

            // when: 첫 조사 페이지를 취소하고 같은 위치부터 다시 처리한다.
            quality.investigateNext(); c.rollback();
            assertThat(jdbc.sql("SELECT phase FROM trip_quality_rebuild").query(String.class).single()).isEqualTo("SEARCH_START");
            quality.investigateNext(); c.commit();
            quality.investigateNext(); c.commit();

            // then
            assertThat(jdbc.sql("SELECT completed FROM trip_quality_rebuild").query(Boolean.class).single()).isTrue();
            assertThat(jdbc.sql("SELECT count(*) FROM forecast_eligible_observation WHERE vehicle_id='bus-1'").query(Integer.class).single()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT observation_batch_id FROM forecast_eligible_observation WHERE vehicle_id='bus-1'").query(Long.class).single()).isEqualTo(next);
            assertThat(jdbc.sql("SELECT count(*) FROM vehicle_observation").query(Integer.class).single()).isEqualTo(6);
            assertThat(jdbc.sql("SELECT max(remaining_seats) FROM vehicle_observation").query(Integer.class).single()).isEqualTo(71);
            assertThat(jdbc.sql("SELECT count(*) FROM vehicle_observation WHERE vehicle_id='bus-2' AND vehicle_trip_key IS NOT NULL").query(Integer.class).single()).isZero();
        }
    }

    @Test
    void 같은_차량의_반복_이상은_진행중인_조사와_품질_버전을_중복_생성하지_않는다() throws Exception {
        // given
        try (var c = connection()) {
            var jdbc = jdbc(c); long version = route(jdbc);
            long first = batch(jdbc, version, 1, 1, 1, 71);
            long second = batch(jdbc, version, 2, 2, 1, 72);
            var quality = new TripQualityRepository(jdbc); c.setAutoCommit(false);
            quality.observationsStored(event(jdbc, version, first)); c.commit();
            var updatedAt = jdbc.sql("SELECT investigated_at FROM trip_quality_rebuild").query(OffsetDateTime.class).single();

            // when
            quality.observationsStored(event(jdbc, version, second)); c.commit();

            // then
            assertThat(jdbc.sql("SELECT count(*) FROM trip_quality_rebuild").query(Integer.class).single()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT quality_revision FROM route").query(Long.class).single()).isEqualTo(2);
            assertThat(jdbc.sql("SELECT investigated_at FROM trip_quality_rebuild").query(OffsetDateTime.class).single()).isEqualTo(updatedAt);
        }
    }

    @Test
    void 문제_차량이_없는_긴_공백에서도_한번에_32묶음만_읽고_다음_실행으로_넘긴다() throws Exception {
        // given
        try (var c = connection()) {
            var jdbc = jdbc(c); long version = route(jdbc);
            batch(jdbc, version, 1, 1, 1, 44);
            for (int n = 2; n <= 50; n++) {
                long b = batch(jdbc, version, n, 2, 1, 44);
                jdbc.sql("UPDATE vehicle_observation SET vehicle_id='other' WHERE observation_batch_id=?").param(b).update();
            }
            long bad = batch(jdbc, version, 51, 3, 1, 71);
            var quality = new TripQualityRepository(jdbc); c.setAutoCommit(false);
            quality.observationsStored(event(jdbc, version, bad)); c.commit();

            // when
            var page = quality.readPage(version, "bus-1", START.plusSeconds(510), bad, true, false);
            quality.investigateNext(); c.commit();

            // then
            assertThat(page).hasSize(32).allMatch(row -> row.observation() == null);
            assertThat(jdbc.sql("SELECT phase FROM trip_quality_rebuild").query(String.class).single()).isEqualTo("SEARCH_START");
            assertThat(jdbc.sql("SELECT last_batch_at FROM trip_quality_rebuild").query(OffsetDateTime.class).single().toInstant()).isEqualTo(START.plusSeconds(190));
            assertThat(jdbc.sql("SELECT count(*) FROM vehicle_observation WHERE vehicle_trip_key IS NOT NULL").query(Integer.class).single()).isZero();
        }
    }

    @Test
    void 다음_편도_관측이_없으면_조사를_완료하지_않고_추가_관측에서_재개한다() throws Exception {
        // given
        try (var c = connection()) {
            var jdbc = jdbc(c); long version = route(jdbc);
            long bad = batch(jdbc, version, 1, 1, 1, 71);
            var quality = new TripQualityRepository(jdbc); c.setAutoCommit(false);
            quality.observationsStored(event(jdbc, version, bad)); c.commit();
            quality.investigateNext(); c.commit(); quality.investigateNext(); c.commit();
            assertThat(jdbc.sql("SELECT completed FROM trip_quality_rebuild").query(Boolean.class).single()).isFalse();
            long next = batch(jdbc, version, 2, 4, 1, 44); c.commit();

            // when
            quality.investigateNext(); c.commit();

            // then
            assertThat(jdbc.sql("SELECT completed FROM trip_quality_rebuild").query(Boolean.class).single()).isTrue();
            assertThat(jdbc.sql("SELECT observation_batch_id FROM forecast_eligible_observation").query(Long.class).single()).isEqualTo(next);
        }
    }

    @Test
    void 과거_발견_작업은_커서부터_재개하고_표본조회와_완료_후_반복호출이_원본을_수정하지_않는다() throws Exception {
        // given
        try (var c = connection()) {
            var jdbc = jdbc(c); long version = route(jdbc);
            batch(jdbc, version, 1, 1, 1, 44); batch(jdbc, version, 2, 2, 1, 71); batch(jdbc, version, 3, 4, 1, 44);
            var maintenance = new TripQualityMaintenance(jdbc); c.setAutoCommit(false);
            assertThat(maintenance.preview(version, START.plusSeconds(60))).containsEntry("scope", "LAST_32_BATCHES_SAMPLE");
            maintenance.applyChunk(version, START.plusSeconds(60), 1); c.commit();
            maintenance.applyChunk(version, START.plusSeconds(60), 1); c.rollback();

            // when
            Map<String, Object> result = Map.of();
            for (int n=0;n<8;n++) {
                result = maintenance.applyChunk(version, START.plusSeconds(60), 1); c.commit();
                if (Boolean.TRUE.equals(result.get("completed"))) { break; }
            }
            var repeated = maintenance.applyChunk(version, START.plusSeconds(60), 1); c.commit();

            // then
            assertThat(result).containsEntry("completed", true);
            assertThat(repeated).containsEntry("completed", true).containsEntry("processedBatches", 0);
            assertThat(jdbc.sql("SELECT maximum_gap_seconds FROM trip_quality_rebuild")
                .query(Integer.class).list()).containsOnly(600);
            assertThat(jdbc.sql("SELECT count(*) FROM forecast_eligible_observation").query(Integer.class).single()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT count(*) FROM vehicle_observation").query(Integer.class).single()).isEqualTo(3);
            assertThatThrownBy(() -> maintenance.applyChunk(version, START.plusSeconds(70), 1)).isInstanceOf(IllegalArgumentException.class);
            c.rollback();
        }
    }

    @Test
    void 노선이_잠겨_있으면_100밀리초_잠금_제한으로_롤백하고_잠금_해제_후_같은_조사를_재개한다() throws Exception {
        // given
        try (var holder = connection(); var worker = connection()) {
            var setup = jdbc(holder);
            long version = route(setup);
            long bad = batch(setup, version, 1, 2, 1, 71);
            holder.setAutoCommit(false);
            new TripQualityRepository(setup).observationsStored(event(setup, version, bad));
            holder.commit();
            new TripQualityRepository(setup).lockRoute(version);
            worker.setAutoCommit(false);
            var quality = new TripQualityRepository(jdbc(worker));

            // when: 다른 transaction이 잡은 노선 잠금은 정해진 시간 이상 기다리지 않는다.
            assertThatThrownBy(quality::investigateNext)
                .isInstanceOf(DataAccessException.class)
                .rootCause().isInstanceOf(SQLException.class)
                .extracting(cause -> ((SQLException) cause).getSQLState()).isEqualTo("55P03");
            worker.rollback();

            // then: 진행 위치를 잃지 않으며 잠금이 풀리면 같은 조사를 처리한다.
            assertThat(jdbc(worker).sql("SELECT phase FROM trip_quality_rebuild WHERE route_version_id=?")
                .param(version).query(String.class).single()).isEqualTo("SEARCH_START");
            holder.rollback();
            assertThat(quality.investigateNext()).isTrue();
            assertThat(jdbc(worker).sql("SELECT phase FROM trip_quality_rebuild WHERE route_version_id=?")
                .param(version).query(String.class).single()).isEqualTo("REPLAY");
            worker.rollback();
        }
    }

    @Test
    void 다음_정상_편도로_복귀해도_조사_중_시간_공백으로_경계를_확인하지_못한_구간은_제외한다() throws Exception {
        // given
        try (var c = connection()) {
            var jdbc = jdbc(c);
            long version = route(jdbc);
            jdbc.sql("UPDATE route_version SET maximum_observation_gap_seconds=60, observation_gap_evidence='synthetic' WHERE id=?")
                .param(version).update();
            batch(jdbc, version, 1, 1, 1, 44);
            long bad = batch(jdbc, version, 2, 2, 1, 71);
            batch(jdbc, version, 10, 3, 1, 44);
            long next = batch(jdbc, version, 11, 4, 1, 44);
            c.setAutoCommit(false);
            var quality = new TripQualityRepository(jdbc);
            quality.observationsStored(event(jdbc, version, bad));

            // when
            quality.investigateLocked(version, "bus-1");
            quality.investigateLocked(version, "bus-1");

            // then
            assertThat(jdbc.sql("SELECT completed FROM trip_quality_rebuild WHERE route_version_id=?")
                .param(version).query(Boolean.class).single()).isTrue();
            assertThat(jdbc.sql("SELECT observation_batch_id FROM forecast_eligible_observation")
                .query(Long.class).list()).containsExactly(next);
            assertThat(jdbc.sql("SELECT count(*) FROM vehicle_observation").query(Integer.class).single()).isEqualTo(4);
            c.rollback();
        }
    }

    @Test
    void 기본_10분을_초과한_관측은_경계_미확인으로_보류하고_다음_회차지_출발에서_복귀한다() throws Exception {
        // given
        try (var c = connection()) {
            var jdbc = jdbc(c);
            long version = route(jdbc);
            batch(jdbc, version, 1, 1, 1, 44);
            long bad = batch(jdbc, version, 2, 2, 1, 71);
            long disconnected = batch(jdbc, version, 63, 3, 1, 44);
            c.setAutoCommit(false);
            var quality = new TripQualityRepository(jdbc);
            quality.observationsStored(event(jdbc, version, bad));
            c.commit();

            // when
            quality.investigateNext(); c.commit();
            quality.investigateNext(); c.commit();

            // then
            assertThat(jdbc.sql("SELECT completed FROM trip_quality_rebuild")
                .query(Boolean.class).single()).isFalse();
            assertThat(jdbc.sql("""
                SELECT t.status FROM vehicle_observation o JOIN vehicle_one_way_trip t ON t.id=o.vehicle_trip_key
                WHERE o.observation_batch_id=?
                """).param(disconnected).query(String.class).single()).isEqualTo("BOUNDARY_UNCONFIRMED");
            assertThat(jdbc.sql("SELECT count(*) FROM forecast_eligible_observation")
                .query(Integer.class).single()).isZero();

            // when
            long next = batch(jdbc, version, 64, 4, 1, 44);
            c.commit();
            quality.investigateNext(); c.commit();

            // then
            assertThat(jdbc.sql("SELECT completed FROM trip_quality_rebuild")
                .query(Boolean.class).single()).isTrue();
            assertThat(jdbc.sql("SELECT observation_batch_id FROM forecast_eligible_observation")
                .query(Long.class).list()).containsExactly(next);
            assertThat(jdbc.sql("SELECT remaining_seats FROM vehicle_observation ORDER BY id")
                .query(Integer.class).list()).containsExactly(44, 71, 44, 44);
        }
    }

    private static JdbcClient jdbc(Connection c) { return JdbcClient.create(new SingleConnectionDataSource(c, true)); }
    private static JdbcClient counted(Connection c, AtomicInteger calls) {
        var proxy = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class[]{Connection.class}, (o, method, args) -> {
            if (method.getName().equals("prepareStatement")) { calls.incrementAndGet(); }
            try { return method.invoke(c, args); } catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
        });
        return jdbc(proxy);
    }
    private static long route(JdbcClient jdbc) {
        long route = jdbc.sql("INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name) VALUES ('900000010','TEST','900000010','test','a','b') RETURNING id").query(Long.class).single();
        long version = jdbc.sql("INSERT INTO route_version(route_id,content_digest,valid_from,turn_sequence) VALUES (?,?,'2026-09-20T00:00:00Z',4) RETURNING id")
            .param(route).param("0".repeat(64)).query(Long.class).single();
        jdbc.sql("INSERT INTO route_stop SELECT ?,n,'s'||n,'stop'||n,CASE WHEN n<=4 THEN 'UP' ELSE 'DOWN' END,true FROM generate_series(1,7) n").param(version).update();
        return version;
    }
    private static long batch(JdbcClient jdbc, long version, int step, int stop, int vehicles, int seats) {
        var at = START.plusSeconds(step * 10L).atOffset(ZoneOffset.UTC);
        long batch = jdbc.sql("""
            INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,requested_at,response_received_at,outcome,normalization_version,collection_strategy_version)
            VALUES (?,?,1,?,?,?,'SUCCESS_ROWS','test','test') RETURNING id
            """).param(version).param(at).param("batch"+step).param(at).param(at).query(Long.class).single();
        jdbc.sql("""
            INSERT INTO vehicle_observation(observation_batch_id,route_version_id,source_row_number,vehicle_id,stop_order,stop_id,running_state,passed_stop_order,remaining_seats)
            SELECT ?,?,n,'bus-'||n,?,'s'||?,2,?,CASE WHEN n=1 THEN ? ELSE 44 END FROM generate_series(1,?) n
            """).param(batch).param(version).param(stop).param(stop).param(stop).param(seats).param(vehicles).update();
        return batch;
    }
    private static VehicleObservationsStored event(JdbcClient jdbc, long version, long batch) {
        var at = jdbc.sql("SELECT response_received_at FROM observation_batch WHERE id=?").param(batch).query(OffsetDateTime.class).single();
        var rows = jdbc.sql("SELECT id,vehicle_id,remaining_seats FROM vehicle_observation WHERE observation_batch_id=? ORDER BY source_row_number")
            .param(batch).query((rs,n)->new VehicleObservationsStored.Row(rs.getLong(1),rs.getString(2),rs.getObject(3,Integer.class))).list();
        return new VehicleObservationsStored(batch,version,at.toInstant(),rows);
    }
}
