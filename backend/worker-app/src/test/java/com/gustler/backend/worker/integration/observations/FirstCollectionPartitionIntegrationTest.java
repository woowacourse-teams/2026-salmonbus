package com.gustler.backend.worker.integration.observations;

import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.gustler.backend.gbis.api.GbisLocationResult;
import com.gustler.backend.gbis.api.dto.BusLocationResponse;
import com.gustler.backend.observations.application.CollectionBatchService;
import com.gustler.backend.observations.application.ObservationCollector;
import com.gustler.backend.observations.domain.CollectionPlan;
import com.gustler.backend.observations.infrastructure.gbis.GbisObservationMapper;
import com.gustler.backend.support.PostgresTestContainer;
import com.gustler.backend.worker.WorkerApplication;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 관측 테이블에 날짜 파티션이 하나도 없는 DB에서 수집만 켠 worker를 띄운다. 유지 작업 없이 여러 날 꺼져 있던 worker의 DB가 이런
 * 구조다. 기동 직후의 첫 수집이 저장한 행이 기본 파티션이 아니라 그날 파티션에 들어가는지 본다.
 *
 * <p>날짜 파티션은 마이그레이션이 끝난 직후에 지운다. 수집은 GBIS를 부르지 않고 실제 수집 원장으로 관측 한 행을 저장한다. worker는
 * 수집 예약을 만드는 즉시 첫 수집을 돌리므로, 시험이 시작하기 전에 첫 줄이 이미 저장돼 있다.
 */
@SpringBootTest(classes = WorkerApplication.class, properties = {
    "collection.enabled=true",
    "collection.route-ids=" + FirstCollectionPartitionIntegrationTest.SOURCE_ROUTE,
    "forecast.enabled=false"
})
@Import({ PostgresTestContainer.class, FirstCollectionPartitionIntegrationTest.DatabaseLeftWithoutPartitions.class })
class FirstCollectionPartitionIntegrationTest {
    static final String SOURCE_ROUTE = "204000057";
    private static final List<String> OBSERVATION_TABLES = List.of("observation_batch", "vehicle_observation");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final List<String> DROPPED = new CopyOnWriteArrayList<>();
    private static final CountDownLatch COLLECTED = new CountDownLatch(1);

    @Autowired
    private JdbcClient jdbc;

    @Test
    void 날짜_파티션이_없는_DB에서_수집만_켠_worker를_띄워도_첫_관측_줄은_그날_파티션에_들어간다() throws InterruptedException {
        // when
        final boolean collected = COLLECTED.await(20, TimeUnit.SECONDS);
        List<Boolean> batchInDayPartition = jdbc.sql("""
            SELECT tableoid::regclass::text = 'observation_batch_p' || to_char(source_date, 'YYYYMMDD')
            FROM observation_batch ORDER BY id LIMIT 1
            """).query(Boolean.class).list();
        List<Boolean> observationInDayPartition = jdbc.sql("""
            SELECT tableoid::regclass::text = 'vehicle_observation_p' || to_char(source_date, 'YYYYMMDD')
            FROM vehicle_observation ORDER BY id LIMIT 1
            """).query(Boolean.class).list();

        // then
        assertSoftly(softly -> {
            softly.assertThat(DROPPED).as("worker가 뜨기 전에 두 테이블의 날짜 파티션을 지웠다")
                .anyMatch(partition -> partition.startsWith("observation_batch_p"))
                .anyMatch(partition -> partition.startsWith("vehicle_observation_p"));
            softly.assertThat(collected).as("기동 직후의 첫 수집이 관측을 저장했다").isTrue();
            softly.assertThat(batchInDayPartition).as("첫 배치 줄이 그날 파티션에 있다").containsExactly(true);
            softly.assertThat(observationInDayPartition).as("첫 관측 줄이 그날 파티션에 있다").containsExactly(true);
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseLeftWithoutPartitions {
        /** 마이그레이션을 끝낸 뒤 수집할 노선을 넣고 관측 두 테이블의 날짜 파티션을 모두 지운다. */
        @Bean
        FlywayMigrationStrategy migrateThenDropObservationPartitions() {
            return flyway -> {
                flyway.migrate();
                JdbcClient admin = JdbcClient.create(flyway.getConfiguration().getDataSource());
                insertRoute(admin);
                for (String table : OBSERVATION_TABLES) {
                    List<String> partitions = admin.sql("""
                        SELECT inhrelid::regclass::text FROM pg_inherits
                        WHERE inhparent = to_regclass(:table) AND inhrelid <> to_regclass(:fallback)
                        """).param("table", table).param("fallback", table + "_default").query(String.class).list();
                    for (String partition : partitions) {
                        admin.sql("DROP TABLE " + partition).update();
                        DROPPED.add(partition);
                    }
                }
            };
        }

        @Bean
        @Primary
        ObservationCollector storingOneObservation(CollectionBatchService ledger, JdbcClient jdbc) {
            ObservationCollector collector = mock(ObservationCollector.class);
            doAnswer(call -> {
                collect(ledger, jdbc, call.getArgument(0));
                COLLECTED.countDown();
                return null;
            }).when(collector).collectOnce(anyString());
            return collector;
        }

        private static void insertRoute(JdbcClient admin) {
            OffsetDateTime validFrom = OffsetDateTime.now(SEOUL).minusDays(1);
            final long routeId = admin.sql("""
                INSERT INTO route (public_route_id, source_id, source_route_id, display_name, start_stop_name,
                    end_stop_name)
                VALUES (:source, 'GBIS', :source, '3330', '출발', '도착') RETURNING id
                """).param("source", SOURCE_ROUTE).query(Long.class).single();
            final long routeVersionId = admin.sql("""
                INSERT INTO route_version (route_id, content_digest, valid_from, turn_sequence)
                VALUES (:route, :digest, :at, 3) RETURNING id
                """).param("route", routeId).param("digest", "0".repeat(64)).param("at", validFrom)
                .query(Long.class).single();
            admin.sql("""
                INSERT INTO route_stop (route_version_id, stop_order, stop_id, name, direction, boarding_allowed)
                VALUES (:version, 1, 'stop-one', '출발', 'UP', true),
                       (:version, 2, 'stop-two', '중간', 'UP', true),
                       (:version, 3, 'stop-three', '도착', 'UP', true)
                """).param("version", routeVersionId).update();
        }

        /** 차량 한 대가 첫 정류장을 막 떠난 관측 한 행을 실제 수집 원장으로 저장하고 커밋한다. */
        private static void collect(CollectionBatchService ledger, JdbcClient jdbc, String sourceRouteId) {
            final long routeVersionId = jdbc.sql("""
                SELECT version.id FROM route_version version JOIN route ON route.id = version.route_id
                WHERE route.source_route_id = :source
                """).param("source", sourceRouteId).query(Long.class).single();
            OffsetDateTime at = OffsetDateTime.now(SEOUL).truncatedTo(ChronoUnit.MILLIS);
            final long batchId = ledger.prepare(new CollectionPlan(routeVersionId, at, UUID.randomUUID().toString()),
                at).batchId();

            BusLocationResponse.BusLocation bus = new BusLocationResponse.BusLocation(
                    "경기70아0001",
                    "first-collection-vehicle",
                    0,
                    sourceRouteId,
                    11,
                    "stop-one",
                    1,
                    2,
                    30,
                    3,
                    1
            );
            ledger.conclude(batchId,
                received -> GbisObservationMapper.response(new GbisLocationResult.Success(
                    at.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.S")), List.of(bus)), received),
                at.plusNanos(1_000_000));
        }
    }
}
