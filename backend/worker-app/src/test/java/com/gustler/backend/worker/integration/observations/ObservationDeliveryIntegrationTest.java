package com.gustler.backend.worker.integration.observations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.gustler.backend.forecasting.application.evaluation.ObservationDeliveryReceiver;
import com.gustler.backend.forecasting.application.evaluation.ObservationDeliveryService;
import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalCandidate;
import com.gustler.backend.forecasting.infrastructure.jdbc.JdbcArrivalObservationQuery;
import com.gustler.backend.forecasting.infrastructure.jdbc.JdbcRecentObservationBatches;
import com.gustler.backend.gbis.api.GbisLocationResult.Success;
import com.gustler.backend.gbis.api.dto.BusLocationResponse.BusLocation;
import com.gustler.backend.observations.api.ObservationDeliveryBatch;
import com.gustler.backend.observations.api.ObservationDeliveryBatch.Kind;
import com.gustler.backend.observations.application.ObservationBatchLedger;
import com.gustler.backend.observations.domain.CollectionPlan;
import com.gustler.backend.observations.infrastructure.gbis.GbisObservationMapper;
import com.gustler.backend.support.PostgresTestContainer;
import com.gustler.backend.worker.WorkerApplication;
import com.gustler.backend.worker.configuration.CollectionRuntimeConfiguration;
import com.gustler.backend.worker.integration.observations.ObservationDeliveryIntegrationTest.ReceiverConfiguration;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(classes = WorkerApplication.class,
    properties = "forecast.evaluation.observation-cache.enabled=true")
@Import({PostgresTestContainer.class, CollectionRuntimeConfiguration.class,
    ReceiverConfiguration.class})
class ObservationDeliveryIntegrationTest {
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-08-19T11:14:00.123456789+09:00");
    private static final String SOURCE_ROUTE = "delivery-test-route";
    @Autowired
    ObservationBatchLedger ledger;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    PlatformTransactionManager transactions;
    @Autowired
    RecordingReceiver receiver;
    @Autowired
    JdbcArrivalObservationQuery arrivals;
    @Autowired
    RouteDataQualityAccess quality;
    private long version;
    private long batch;
    private CollectionPlan plan;

    @BeforeEach
    void 수집할_노선을_준비한다() {
        final long route = jdbc.sql("""
            INSERT INTO route (public_route_id, source_id, source_route_id,
                display_name, start_stop_name, end_stop_name)
            VALUES (:source, 'GBIS', :source, '전달 검증', '출발', '도착') RETURNING id
            """).param("source", SOURCE_ROUTE).query(Long.class).single();
        version = jdbc.sql("""
            INSERT INTO route_version (route_id, content_digest, valid_from, turn_sequence)
            VALUES (:route, :digest, :at, 2) RETURNING id
            """).param("route", route).param("digest", "0".repeat(64)).param("at", AT)
            .query(Long.class).single();
        jdbc.sql("""
            INSERT INTO route_stop (route_version_id, stop_order, stop_id, name, direction, boarding_allowed)
            VALUES (:version, 1, 'stop-one', '출발', 'UP', true),
                   (:version, 2, 'stop-two', '반환', 'UP', true),
                   (:version, 3, 'stop-three', '도착', 'DOWN', true)
            """).param("version", version).update();
        plan = new CollectionPlan(version, AT, "delivery-test");
        batch = ledger.reserve(plan, AT).batchId();
        ledger.markDispatching(batch, AT, AT, "a");
    }

    @AfterEach
    void 저장한_자료와_수신_상태를_정리한다() {
        receiver.deliveries.clear();
        receiver.fail = false;
        receiver.accept = true;
        receiver.onReceive = (id, batch) -> {
        };
        jdbc.sql("""
            TRUNCATE vehicle_observation, observation_batch, route_stop, route_version, route,
                daily_call_quota RESTART IDENTITY CASCADE
            """).update();
    }

    @Test
    void 관측_저장_트랜잭션이_커밋되면_수집한_차량_관측을_캐시에_넣는다() {
        // given
        JdbcRecentObservationBatches source = spy(new JdbcRecentObservationBatches(jdbc));
        ObservationDeliveryService memory = memory(source);
        receiver.onReceive = memory::receive;

        // when
        save();
        List<ArrivalCandidate> result = new TransactionTemplate(transactions).execute(status -> {
            final long revision = quality.lock(version);
            return memory.begin(AT.plusSeconds(2).toInstant()).read(arrivals, version, revision, false,
                receiver.deliveries.getFirst().batch().rows().getFirst().vehicleId(), AT.toInstant(), 100);
        });

        // then
        assertThat(result).singleElement().satisfies(row -> {
            assertThat(row.remainingSeats()).isEqualTo(20);
            assertThat(row.observedAt()).isEqualTo(
                jdbc.sql("SELECT response_received_at FROM observation_batch WHERE id = ?")
                    .param(batch)
                    .query(OffsetDateTime.class)
                    .single()
                    .toInstant());
        });
        verify(source, never()).restore(anyLong(), anyList(), anyInt());
        assertDeliveryTableAbsent();
    }

    @Test
    void 커밋_후_전달이_지연돼도_정산은_DB에서_복원한다() throws Exception {
        // given
        JdbcRecentObservationBatches source = spy(new JdbcRecentObservationBatches(jdbc));
        ObservationDeliveryService memory = memory(source);
        CountDownLatch committed = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        receiver.onReceive = (id, batch) -> {
            committed.countDown();
            try {
                if (!resume.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("전달 재개 대기 초과");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
            memory.receive(id, batch);
        };
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> collecting = executor.submit(this::save);
            try {
                assertThat(committed.await(5, TimeUnit.SECONDS)).isTrue();

                // when
                List<ArrivalCandidate> result = new TransactionTemplate(transactions).execute(status -> {
                    final long revision = quality.lock(version);
                    String vehicle = jdbc.sql("SELECT vehicle_id FROM vehicle_observation")
                        .query(String.class)
                        .single();
                    return memory.begin(AT.plusSeconds(2).toInstant()).read(arrivals, version, revision, false,
                        vehicle, AT.toInstant(), 100);
                });

                // then
                assertThat(result).hasSize(1);
                verify(source).restore(eq(version), anyList(), anyInt());
            } finally {
                resume.countDown();
            }
            collecting.get(5, TimeUnit.SECONDS);
        }
    }

    private ObservationDeliveryService memory(JdbcRecentObservationBatches source) {
        return new ObservationDeliveryService(source, Clock.fixed(AT.plusSeconds(2).toInstant(), ZoneOffset.UTC),
            100, Duration.ofMinutes(30));
    }

    @Test
    void 관측_저장이_롤백되면_해당_배치의_차량_관측은_캐시에_남지_않는다() {
        // given
        TransactionTemplate transaction = new TransactionTemplate(transactions);

        // when
        transaction.executeWithoutResult(status -> {
            save();
            assertDeliveryTableAbsent();
            status.setRollbackOnly();
        });

        // then
        assertThat(count("vehicle_observation")).isZero();
        assertDeliveryTableAbsent();
        assertThat(receiver.deliveries).isEmpty();
    }

    @Test
    void 관측_저장_트랜잭션이_커밋되기_전에는_수집한_차량_관측을_캐시에_넣지_않는다() {
        // given
        TransactionTemplate transaction = new TransactionTemplate(transactions);

        // when
        transaction.executeWithoutResult(status -> {
            save();
            assertThat(count("vehicle_observation")).isEqualTo(1);
            assertThat(receiver.deliveries).isEmpty();
        });

        // then
        assertThat(receiver.deliveries).hasSize(1);
        ObservationDeliveryBatch delivered = receiver.deliveries.getFirst().batch();
        assertThat(delivered.batchId()).isEqualTo(batch);
        assertThat(delivered.rows()).hasSize(1);
        assertThat(delivered.rows().getFirst().observationId()).isEqualTo(
            jdbc.sql("SELECT id FROM vehicle_observation").query(Long.class).single());
        assertThat(delivered.rows().getFirst().passedStopOrder()).isEqualTo(
            jdbc.sql("SELECT passed_stop_order FROM vehicle_observation").query(Integer.class).single());
        assertThat(delivered.rows().getFirst().remainingSeats()).isEqualTo(20);
        assertDeliveryTableAbsent();
    }

    @Test
    void 캐시_반영에_실패해도_커밋된_관측과_수집_성공_기록은_유지된다() {
        // given
        receiver.fail = true;

        // when
        save();

        // then
        assertThat(count("vehicle_observation")).isEqualTo(1);
        assertDeliveryTableAbsent();
        assertThat(receiver.deliveries).isEmpty();
        assertThat(jdbc.sql("SELECT outcome FROM observation_batch WHERE id = ?")
                .param(batch).query(String.class).single()).isEqualTo("SUCCESS_ROWS");
    }

    @Test
    void 같은_수집_배치를_다시_저장하면_새로운_전달로_구분한다() {
        // given
        save();

        // when
        final long reused = ledger.reserve(plan, AT.plusSeconds(1)).batchId();
        ledger.markDispatching(reused, AT, AT.plusSeconds(1), "a");
        save();

        // then
        assertThat(reused).isEqualTo(batch);
        assertThat(receiver.deliveries).hasSize(3);
        assertThat(receiver.deliveries.stream()
                .map(item -> item.batch().attempt())
                .toList())
            .containsExactly(1, 2, 2);
        assertThat(receiver.deliveries.stream()
                .map(item -> item.batch().kind())
                .toList())
            .containsExactly(Kind.STORED,
                Kind.CLEARED, Kind.STORED);
        assertThat(receiver.deliveries.get(1).batch().rows()).isEmpty();
        assertThat(count("vehicle_observation")).isEqualTo(1);
    }

    @Test
    void 관측_전달_테이블_없이_수집한_관측을_캐시에_반영한다() {
        // given
        JdbcRecentObservationBatches source = spy(new JdbcRecentObservationBatches(jdbc));
        ObservationDeliveryService memory = memory(source);
        receiver.onReceive = memory::receive;
        assertDeliveryTableAbsent();

        // when
        save();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            final long revision = quality.lock(version);
            String vehicle = receiver.deliveries.getFirst().batch().rows().getFirst().vehicleId();
            memory.begin(AT.plusSeconds(2).toInstant())
                .read(arrivals, version, revision, false, vehicle, AT.toInstant(), 100);
        });

        // then
        assertDeliveryTableAbsent();
        assertThat(count("vehicle_observation")).isEqualTo(1);
        verify(source, never()).restore(anyLong(), anyList(), anyInt());
    }

    private void assertDeliveryTableAbsent() {
        assertThat(jdbc.sql("SELECT to_regclass('observation_delivery') IS NULL")
                    .query(Boolean.class).single()).isTrue();
    }

    private void save() {
        BusLocation bus = new BusLocation("경기70아0001", "delivery-test-vehicle", 0, SOURCE_ROUTE, 11,
            "stop-one", 1, 2, 20, 3, 1);
        ledger.conclude(batch,
            at -> GbisObservationMapper.response(new Success("2026-08-19 11:14:00.9", List.of(bus)), at),
            AT.plusSeconds(2));
    }

    private int count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }

    record Delivery(long id, ObservationDeliveryBatch batch) {
    }

    static class RecordingReceiver implements ObservationDeliveryReceiver {
        final List<Delivery> deliveries = new ArrayList<>();
        boolean fail;
        boolean accept = true;
        BiConsumer<Long, ObservationDeliveryBatch> onReceive = (id, batch) -> {
        };

        @Override
        public boolean receive(final long id, ObservationDeliveryBatch batch) {
            if (fail) {
                throw new IllegalStateException("전달 실패");
            }
            deliveries.add(new Delivery(id, batch));
            onReceive.accept(id, batch);
            return accept;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ReceiverConfiguration {
        @Bean
        @Primary
        RecordingReceiver observationDeliveryReceiver() {
            return new RecordingReceiver();
        }
    }
}
