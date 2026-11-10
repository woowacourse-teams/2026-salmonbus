package com.gustler.backend.forecasting.infrastructure.alighting;

import com.gustler.backend.forecasting.infrastructure.capacity.VehicleCapacityCache;
import com.gustler.backend.forecasting.infrastructure.capacity.JdbcVehicleCapacityStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.domain.model.RouteDirection;
import com.gustler.backend.forecasting.domain.model.RouteStop;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareFeatureSnapshot;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareFeatureVector;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import com.gustler.backend.support.ConfirmedTripFixture;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@ForecastingIntegrationTest
@Transactional
class JdbcAlightingAwareFeatureQueryTest {
    @Autowired
    JdbcClient jdbc;
    @TempDir
    Path checkpoint;
    private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");
    private long version, first, sourceBatch, source;
    private RouteStops stops;
    private AlightingAwareRouteStatistics statistics;

    @BeforeEach
    void setup() {
        String routeName = "alighting-" + UUID.randomUUID().toString().substring(0, 12);
        final long route = jdbc.sql(
            "INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name) VALUES(?,'GBIS',?,?, 'A','B') RETURNING id")
            .params(routeName, routeName, routeName).query(Long.class).single();
        version = jdbc.sql("INSERT INTO route_version(route_id,content_digest,valid_from) VALUES(?,?,?) RETURNING id")
            .params(route, "0".repeat(64), at(NOW.minusSeconds(10000))).query(Long.class).single();
        for (int i = 1; i <= 3; i++) {
            jdbc.sql(
                "INSERT INTO route_stop(route_version_id,stop_order,stop_id,name,direction,boarding_allowed) VALUES(?,?,?,?,'UP',true)")
                .params(version, i, "s" + i, "정류장" + i).update();
        }
        stops = new RouteStops(version, routeName, List.of(new RouteStop(version, 1, "s1", true, RouteDirection.UP),
            new RouteStop(version, 2, "s2", true, RouteDirection.UP),
            new RouteStop(version, 3, "s3", true, RouteDirection.UP)), routeName);
        Map<String, Map<String, Double>> tables = new HashMap<String, Map<String, Double>>();
        for (String t : List.of("lam", "lam_base", "net_tab", "full_tab", "dep_tab", "stop_net_base")) {
            tables.put(t, Map.of());
        }
        statistics = new AlightingAwareRouteStatistics(routeName,
            List.of(new AlightingAwareRouteStatistics.Stop(1, "s1", "정류장1", false, true),
                new AlightingAwareRouteStatistics.Stop(2, "s2", "정류장2", false, true),
                new AlightingAwareRouteStatistics.Stop(3, "s3", "정류장3", false, true)),
            2, tables, Map.of(), Set.of());
        jdbc.sql("""
            INSERT INTO vehicle_capacity(source_id,vehicle_id,capacity,evidence_at)
            VALUES('GBIS','bus',68,?) ON CONFLICT(source_id,vehicle_id) DO UPDATE SET evidence_at=EXCLUDED.evidence_at
            """).param(at(NOW.minusSeconds(10000))).update();
        first = batch(-7200, 1);
        observation(first, 68);
        batch(-20, 0); // Empty collection still interrupts consecutive batch slope.
        sourceBatch = batch(0, 1);
        source = observation(sourceBatch, 4);
    }

    @Test
    void 전체_이력과_빈_배치_순서를_복구한다() {
        AlightingAwareFeatureSnapshot a = query().read(sourceBatch, stops, statistics).get(source);
        AlightingAwareFeatureSnapshot b = query().read(sourceBatch, stops, statistics).get(source);
        assertSoftly(softly -> {
            softly.assertThat(a.source().batchRank()).isEqualTo(2);
            softly.assertThat(a.forTarget(2).seat88()[53]).isEqualTo(68);
            softly.assertThat(a.forTarget(2).seat88()[13]).isEqualTo(1);
            softly.assertThat(a.forTarget(2).seat88()).containsExactly(b.forTarget(2).seat88());
        });
    }

    @Test
    void 검증된_이력_시작점이_없으면_거부한다() {
        assertThatThrownBy(() -> new JdbcAlightingAwareFeatureQuery(jdbc, new VehicleCapacityCache(
            new JdbcVehicleCapacityStore(jdbc)), "", 100, true, "").read(sourceBatch, stops, statistics))
            .hasMessageContaining("ORIGIN_UNVERIFIED");
    }

    @Test
    void 원본_행이_누락되면_거부한다() {
        jdbc.sql("UPDATE observation_batch SET stored_rows=2 WHERE id=?").param(first).update();
        assertThatThrownBy(() -> query().read(sourceBatch, stops, statistics))
            .hasMessageContaining("SOURCE_ROWS_MISSING");
    }

    @Test
    void 행_제한을_넘기면_이력을_자르지_않고_거부한다() {
        assertThatThrownBy(() -> new JdbcAlightingAwareFeatureQuery(jdbc, new VehicleCapacityCache(
            new JdbcVehicleCapacityStore(jdbc)), version + ":" + first, 1, true, "").read(sourceBatch,
            stops, statistics))
            .hasMessageContaining("ROW_LIMIT_EXCEEDED");
    }

    @Test
    void 배치를_나눠_복구하고_재시작해도_전체_원본과_일치한다() {
        AlightingAwareFeatureVector expected = query().read(sourceBatch, stops, statistics).get(source).forTarget(2);
        final int steps = new AlightingAwareHistoryBootstrap(jdbc).rebuild(stops, statistics, first, sourceBatch, 1, 100,
            checkpoint.toString());
        assertThat(steps).isEqualTo(3);
        JdbcAlightingAwareFeatureQuery restarted = new JdbcAlightingAwareFeatureQuery(jdbc, new VehicleCapacityCache(
            new JdbcVehicleCapacityStore(jdbc)), version + ":" + first, 100, true,
            checkpoint.toString());
        AlightingAwareFeatureVector actual = restarted.read(sourceBatch, stops, statistics).get(source).forTarget(2);
        assertSoftly(softly -> {
            softly.assertThat(actual.seat88()).containsExactly(expected.seat88());
            softly.assertThat(actual.probability80()).containsExactly(expected.probability80());
            softly.assertThat(new AlightingAwareHistoryBootstrap(jdbc).rebuild(stops, statistics, first, sourceBatch, 1, 100,
                checkpoint.toString())).isZero();
        });
    }

    @Test
    void 과거_미발행_배치를_재처리해도_체크포인트는_후퇴하지_않는다() {
        AlightingAwareFeatureVector expected = query().read(sourceBatch, stops, statistics).get(source).forTarget(2);
        JdbcAlightingAwareFeatureQuery checkpointQuery = new JdbcAlightingAwareFeatureQuery(jdbc, new VehicleCapacityCache(
            new JdbcVehicleCapacityStore(jdbc)), version + ":" + first, 100, true,
            checkpoint.toString());
        checkpointQuery.read(sourceBatch, stops, statistics);
        final long later = batch(60, 1);
        observation(later, 70);
        checkpointQuery.read(later, stops, statistics);
        AlightingAwareFeatureVector actual = new JdbcAlightingAwareFeatureQuery(jdbc, new VehicleCapacityCache(
            new JdbcVehicleCapacityStore(jdbc)), version + ":" + first, 100, true, checkpoint.toString())
            .read(sourceBatch, stops, statistics).get(source).forTarget(2);
        assertSoftly(softly -> {
            softly.assertThat(actual.seat88()).containsExactly(expected.seat88());
            softly.assertThat(actual.probability80()).containsExactly(expected.probability80());
        });
        AlightingAwareHistorySeedReader reader = new AlightingAwareHistorySeedReader(checkpoint.toString(), 100);
        AlightingAwareHistorySeedReader.Seed newer = reader.read(version);
        assertThat(newer.batchId()).isEqualTo(later);
        reader.write(version,
            new AlightingAwareHistorySeedReader.Seed(newer.observations(), sourceBatch, 2, NOW, newer.prefix(), newer.events()));
        assertThat(reader.read(version).batchId()).isEqualTo(later);
    }

    @Test
    void 차량_식별자가_없어도_배치_좌석에는_포함하고_예보는_만들지_않는다() {
        jdbc.sql(
            """
                INSERT INTO vehicle_observation(observation_batch_id,route_version_id,source_row_number,vehicle_id,stop_order,stop_id,
                    passed_stop_order,running_state,remaining_seats) VALUES(?,?,1,NULL,2,'s2',2,2,7)
                """)
            .params(sourceBatch, version).update();
        jdbc.sql("UPDATE observation_batch SET stored_rows=2 WHERE id=?").param(sourceBatch).update();
        Map<Long, AlightingAwareFeatureSnapshot> snapshots = query().read(sourceBatch, stops, statistics);
        assertThat(snapshots).containsOnlyKeys(source);
        AlightingAwareFeatureVector input = snapshots.get(source).forTarget(3);
        assertSoftly(softly -> {
            softly.assertThat(input.seat88()[59]).isEqualTo(.25);
            softly.assertThat(input.seat88()[68]).isEqualTo((double) (7f / 68));
        });
    }

    @Test
    void 미래의_최대_좌석이_과거_입력을_바꾸지_않는다() {
        final long later = batch(60, 1);
        observation(later, 70);
        assertThat(query().read(sourceBatch, stops, statistics).get(source).forTarget(2).seat88()[53]).isEqualTo(68);
    }

    @Test
    void 확인_시각_판본은_빈_배치를_제외하고_물리적_단절을_유지한다() {
        // given
        jdbc.sql("UPDATE observation_batch SET ingestion_origin='LIVE',input_confirmed_at=response_received_at+interval '1 second' WHERE route_version_id=?")
            .param(version).update();
        AlightingAwareRouteStatistics confirmed = new AlightingAwareRouteStatistics(statistics.route(), statistics.stops(),
            statistics.minutesPerStop(), statistics.tables(), statistics.targetBands(), statistics.holidays(), true);

        // when
        AlightingAwareFeatureSnapshot snapshot = query().read(sourceBatch, stops, confirmed).get(source);

        // then
        assertSoftly(softly -> {
            softly.assertThat(snapshot.source().receivedAt()).isEqualTo(NOW.plusSeconds(1));
            softly.assertThat(snapshot.source().sourceReceivedAt()).isEqualTo(NOW);
            softly.assertThat(snapshot.source().batchRank()).isEqualTo(1);
            softly.assertThat(snapshot.forTarget(2).seat88()[13]).isEqualTo(1);
            softly.assertThat(snapshot.capacity()).isEqualTo(68);
        });
    }

    @Test
    void 새_판본_체크포인트를_복구하고_재시작해도_입력이_같다() {
        // given
        jdbc.sql("UPDATE observation_batch SET ingestion_origin='LIVE',input_confirmed_at=response_received_at+interval '1 second' WHERE route_version_id=?")
            .param(version).update();
        AlightingAwareRouteStatistics confirmed = new AlightingAwareRouteStatistics(statistics.route(), statistics.stops(),
            statistics.minutesPerStop(), statistics.tables(), statistics.targetBands(), statistics.holidays(), true);
        AlightingAwareFeatureVector expected = query().read(sourceBatch, stops, confirmed).get(source).forTarget(2);

        // when
        final int steps = new AlightingAwareHistoryBootstrap(jdbc).rebuild(stops, confirmed, first, sourceBatch, 1, 100,
            checkpoint.toString());
        AlightingAwareFeatureVector restored = new JdbcAlightingAwareFeatureQuery(jdbc, new VehicleCapacityCache(
            new JdbcVehicleCapacityStore(jdbc)), "", 100, true, checkpoint.toString())
            .read(sourceBatch, stops, confirmed).get(source).forTarget(2);

        // then
        assertSoftly(softly -> {
            softly.assertThat(steps).isEqualTo(2);
            softly.assertThat(restored.seat88()).containsExactly(expected.seat88());
            softly.assertThat(restored.probability80()).containsExactly(expected.probability80());
        });
    }

    private JdbcAlightingAwareFeatureQuery query() {
        return new JdbcAlightingAwareFeatureQuery(jdbc, new VehicleCapacityCache(
            new JdbcVehicleCapacityStore(jdbc)), version + ":" + first, 100, true, "");
    }

    private long batch(final int seconds, final int rows) {
        OffsetDateTime time = at(NOW.plusSeconds(seconds));
        return jdbc
            .sql(
                """
                    INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,requested_at,response_received_at,
                        outcome,stored_rows,normalization_version,collection_strategy_version)
                    VALUES(?,?,1,?,?,?,?,?,'normalization-v1.0.0','adaptive-kst-v1.0.1') RETURNING id
                    """)
            .params(version, time, UUID.randomUUID().toString(), time, time, outcome(rows), rows).query(Long.class)
            .single();
    }

    private long observation(final long batch, final int seats) {
        final long id = jdbc
            .sql(
                """
                    INSERT INTO vehicle_observation(observation_batch_id,route_version_id,source_row_number,vehicle_id,stop_order,stop_id,
                        passed_stop_order,running_state,remaining_seats) VALUES(?,?,0,'bus',1,'s1',1,2,?) RETURNING id
                    """)
            .params(batch, version, seats).query(Long.class).single();
        return ConfirmedTripFixture.include(jdbc, id);
    }

    private static String outcome(final int rows) {
        if (rows == 0) {
            return "SUCCESS_EMPTY";
        }
        return "SUCCESS_ROWS";
    }

    private static OffsetDateTime at(Instant time) {
        return time.atOffset(ZoneOffset.UTC);
    }
}
