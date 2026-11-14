package com.gustler.backend.worker.integration.observations;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.gbis.api.GbisLocationResult;
import com.gustler.backend.gbis.api.dto.BusLocationResponse.BusLocation;
import com.gustler.backend.observations.api.CollectionQualityHook;
import com.gustler.backend.observations.api.ObservationDeliveryBatch;
import com.gustler.backend.observations.api.ObservationDeliveryHook;
import com.gustler.backend.observations.api.VehicleObservationsStored;
import com.gustler.backend.observations.application.ObservationBatchLedger;
import com.gustler.backend.observations.application.ObservationLoader;
import com.gustler.backend.observations.domain.CollectionPlan;
import com.gustler.backend.observations.infrastructure.gbis.GbisObservationMapper;
import com.gustler.backend.observations.infrastructure.jpa.JpaCollectionBatchRepository;
import com.gustler.backend.observations.infrastructure.jpa.ObservationBatchEntityRepository;
import com.gustler.backend.observations.infrastructure.jpa.VehicleObservationBulkInsert;
import com.gustler.backend.observations.infrastructure.jpa.VehicleObservationEntityRepository;
import com.gustler.backend.support.PostgresTestContainer;
import com.gustler.backend.worker.WorkerApplication;
import com.gustler.backend.worker.configuration.CollectionRuntimeConfiguration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 같은 수집 응답을 관측 한 문장 저장의 꺼짐·켜짐으로 각각 저장해 결과가 같은지 확인한다.
 *
 * <p>두 저장은 같은 배치에서 각자 롤백하는 트랜잭션으로 돌려 서로의 좌석 판정·정원 기록에 영향을 주지 않는다.
 * 관측 번호는 두 저장에서 다르므로 상류 행 번호로 바꿔 비교한다. 좌석 품질 훅은 실제 구성의 것을 쓴다.
 */
@SpringBootTest(classes = WorkerApplication.class)
@Import({ PostgresTestContainer.class, CollectionRuntimeConfiguration.class })
class ObservationBulkInsertEquivalenceTest {
    private static final String ROUTE_3330 = "204000057";
    private static final String STOP_1 = "205000217";
    private static final String STOP_2 = "277103149";
    private static final String STOP_3 = "208000069";
    private static final String QUERY_TIME = "2026-08-19 11:14:04.9";
    private static final OffsetDateTime SCHEDULED_AT = OffsetDateTime.parse("2026-08-19T11:14:00+09:00");
    private static final OffsetDateTime RESERVED_AT = OffsetDateTime.parse("2026-08-19T11:14:04.700+09:00");
    private static final OffsetDateTime REQUESTED_AT = OffsetDateTime.parse("2026-08-19T11:14:04.800+09:00");
    private static final OffsetDateTime RESPONSE_RECEIVED_AT = OffsetDateTime.parse("2026-08-19T11:14:04.911+09:00");
    private static final int NORMAL_BUS = 0;
    private static final int LOW_FLOOR_BUS = 1;
    private static final int MOVING = 0;
    private static final int ARRIVED = 1;
    private static final int DEPARTED = 2;
    private static final int NEVER_OBSERVED = 3;

    @Autowired
    private ObservationBatchLedger ledger;

    @Autowired
    private ObservationBatchEntityRepository batchEntities;

    @Autowired
    private VehicleObservationEntityRepository observationEntities;

    @Autowired
    private VehicleObservationBulkInsert bulkInsert;

    @Autowired
    private List<CollectionQualityHook> qualityHooks;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private long batchId;

    static Stream<Arguments> 수집_응답() {
        return Stream.of(
            Arguments.of("보통 응답", List.of(
                bus("eq-1", "경기70아0001", NORMAL_BUS, STOP_1, 1, DEPARTED, 30, 3),
                bus("eq-2", "경기70아0002", LOW_FLOOR_BUS, STOP_2, 2, MOVING, 0, 1),
                bus("eq-3", "경기70아0003", NORMAL_BUS, STOP_3, 3, ARRIVED, 12, 4))),
            Arguments.of("차량 번호 없는 줄", List.of(
                bus(null, null, NORMAL_BUS, STOP_2, 2, DEPARTED, 20, 2),
                bus("eq-1", "경기70아0001", NORMAL_BUS, STOP_1, 1, ARRIVED, 25, 2))),
            Arguments.of("좌석 모름", List.of(
                bus("eq-1", "경기70아0001", NORMAL_BUS, STOP_1, 1, DEPARTED, -1, 3),
                bus("eq-2", "경기70아0002", NORMAL_BUS, STOP_2, 2, DEPARTED, null, 3))),
            Arguments.of("혼잡도 없음", List.of(
                bus("eq-1", "경기70아0001", NORMAL_BUS, STOP_1, 1, DEPARTED, 30, 0),
                bus("eq-2", "경기70아0002", NORMAL_BUS, STOP_2, 2, DEPARTED, 30, null))),
            Arguments.of("좌석 품질 훅이 좌석을 가리는 줄", List.of(
                bus("eq-1", "경기70아0001", NORMAL_BUS, STOP_1, 1, DEPARTED, 71, 3),
                bus("eq-2", "경기70아0002", NORMAL_BUS, STOP_2, 2, DEPARTED, 43, 3))),
            Arguments.of("한 대뿐", List.of(
                bus("eq-1", "경기70아0001", NORMAL_BUS, STOP_1, 1, DEPARTED, 30, 3))),
            Arguments.of("0대", List.of()),
            Arguments.of("저장 불가 줄이 섞인 응답", List.of(
                bus("eq-1", "경기70아0001", NORMAL_BUS, STOP_1, 1, DEPARTED, 30, 3),
                bus("eq-2", "경기70아0002", NORMAL_BUS, STOP_2, 2, NEVER_OBSERVED, 30, 3),
                bus("eq-3", "경기70아0003", NORMAL_BUS, STOP_3, null, DEPARTED, 30, 3),
                bus("eq-4", "경기70아0004", NORMAL_BUS, STOP_3, 3, MOVING, 30, 3))));
    }

    @BeforeEach
    void 노선과_정류소를_저장하고_수집_전송을_기록한다() {
        final long routeId = jdbc.sql("""
            INSERT INTO route (public_route_id, source_id, source_route_id,
                display_name, start_stop_name, end_stop_name)
            VALUES (?, 'GBIS', ?, '3330', '범계역', '강남역') RETURNING id
            """).params(ROUTE_3330, ROUTE_3330).query(Long.class).single();
        final long routeVersionId = jdbc.sql("""
            INSERT INTO route_version (route_id, content_digest, valid_from, turn_sequence)
            VALUES (?, ?, ?, 2) RETURNING id
            """).params(routeId, "0".repeat(64), SCHEDULED_AT).query(Long.class).single();
        jdbc.sql("""
            INSERT INTO route_stop (route_version_id, stop_order, stop_id, name, direction, boarding_allowed)
            VALUES (?, 1, ?, '범계역', 'UP', true), (?, 2, ?, '안양대교(경유)', 'UP', false),
                   (?, 3, ?, '안양역', 'DOWN', true)
            """).params(routeVersionId, STOP_1, routeVersionId, STOP_2, routeVersionId, STOP_3).update();
        batchId = ledger.reserve(new CollectionPlan(routeVersionId, SCHEDULED_AT, "bulk-equivalence"), RESERVED_AT)
            .batchId();
        ledger.markDispatching(batchId, RESERVED_AT, REQUESTED_AT, "a");
    }

    @AfterEach
    void 저장한_자료를_정리한다() {
        jdbc.sql("DELETE FROM vehicle_capacity WHERE vehicle_id LIKE 'eq-%'").update();
        jdbc.sql("""
            TRUNCATE vehicle_observation, observation_batch, route_stop, route_version, route,
                daily_call_quota RESTART IDENTITY CASCADE
            """).update();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("수집_응답")
    void 켜짐과_꺼짐은_같은_관측과_배치_결과와_훅_입력을_남긴다(String scenario, List<BusLocation> buses) {
        // when
        Outcome jpa = storeAndRollBack(false, buses);
        Outcome bulk = storeAndRollBack(true, buses);

        // then
        assertThat(bulk).isEqualTo(jpa);
        assertThat(jpa.batch()).isNotEmpty();
    }

    @Test
    void 켜짐에서도_좌석_품질_훅이_가린_좌석은_전달되지_않고_정원은_올라간다() {
        // given
        List<BusLocation> buses = List.of(
            bus("eq-1", "경기70아0001", NORMAL_BUS, STOP_1, 1, DEPARTED, 71, 3),
            bus("eq-2", "경기70아0002", NORMAL_BUS, STOP_2, 2, DEPARTED, 43, 3));

        // when
        Outcome bulk = storeAndRollBack(true, buses);

        // then
        assertThat(bulk.delivered()).contains(Arrays.asList(0, "eq-1", null, 1, 3, 1, DEPARTED),
            Arrays.asList(1, "eq-2", 43, 2, 3, 2, DEPARTED));
        assertThat(bulk.observations().getFirst()).contains("\"seat_quality_status\": \"REVIEW\"",
            "\"reported_remaining_seats\": 71");
        assertThat(bulk.capacities()).singleElement().asString().contains("\"vehicle_id\": \"eq-2\"",
            "\"capacity\": 43", "\"evidence_source_row\": 1");
    }

    private Outcome storeAndRollBack(final boolean bulk, List<BusLocation> buses) {
        RecordingHooks recording = new RecordingHooks();
        List<CollectionQualityHook> hooks = new ArrayList<>(qualityHooks);
        hooks.add(recording);
        ObservationLoader loader = new ObservationLoader(
            new JpaCollectionBatchRepository(batchEntities, observationEntities, bulkInsert, bulk),
            hooks, List.of(recording));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        return transaction.execute(status -> {
            status.setRollbackOnly();
            loader.load(batchId, GbisObservationMapper.from(new GbisLocationResult.Success(QUERY_TIME, buses)),
                GbisObservationMapper.collect(buses), RESPONSE_RECEIVED_AT);
            Map<Long, Integer> sourceRows = sourceRowsById();
            return new Outcome(observations(), batch(), capacities(), recording.stored(sourceRows),
                recording.delivered(sourceRows));
        });
    }

    private List<String> observations() {
        return jdbc.sql("""
            SELECT (to_jsonb(observation) - 'id')::text FROM vehicle_observation observation
            WHERE observation_batch_id = ? ORDER BY source_row_number
            """).param(batchId).query(String.class).list();
    }

    private String batch() {
        return jdbc.sql("SELECT to_jsonb(batch)::text FROM observation_batch batch WHERE id = ?")
            .param(batchId).query(String.class).single();
    }

    private List<String> capacities() {
        return jdbc.sql("""
            SELECT ((to_jsonb(saved) - 'updated_at' - 'evidence_observation_id')
                || jsonb_build_object('evidence_source_row', observation.source_row_number))::text
            FROM vehicle_capacity saved
            LEFT JOIN vehicle_observation observation ON observation.id = saved.evidence_observation_id
            WHERE saved.vehicle_id LIKE 'eq-%' ORDER BY saved.vehicle_id
            """).query(String.class).list();
    }

    private Map<Long, Integer> sourceRowsById() {
        return jdbc.sql("SELECT id, source_row_number FROM vehicle_observation WHERE observation_batch_id = ?")
            .param(batchId)
            .query((resultSet, rowNumber) -> Map.entry(resultSet.getLong("id"), resultSet.getInt("source_row_number")))
            .list().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static BusLocation bus(String vehicleId, String plateNumber, final int vehicleType, String stopId,
        Integer stopOrder, final int runningState, Integer remainingSeats, Integer crowdLevel) {
        return new BusLocation(plateNumber, vehicleId, vehicleType, ROUTE_3330, 11, stopId, stopOrder, runningState,
            remainingSeats, crowdLevel, 1);
    }

    private record Outcome(List<String> observations, String batch, List<String> capacities, List<List<Object>> stored,
        List<List<Object>> delivered) {
    }

    /** 품질 훅과 전달 훅이 받은 값을 관측 번호 대신 상류 행 번호로 남긴다. 좌석을 가리지 않는다. */
    private static final class RecordingHooks implements CollectionQualityHook, ObservationDeliveryHook {
        private final List<VehicleObservationsStored> storedEvents = new ArrayList<>();
        private final List<ObservationDeliveryBatch> deliveries = new ArrayList<>();

        @Override
        public Set<Long> observationsStored(VehicleObservationsStored observations) {
            storedEvents.add(observations);
            return Set.of();
        }

        @Override
        public void record(ObservationDeliveryBatch batch) {
            deliveries.add(batch);
        }

        List<List<Object>> stored(Map<Long, Integer> sourceRows) {
            return storedEvents.stream()
                .flatMap(event -> Stream.concat(
                    Stream.of(Arrays.<Object>asList(event.batchId(), event.routeVersionId(), event.observedAt())),
                    event.rows().stream().map(row -> Arrays.<Object>asList(sourceRows.get(row.observationId()),
                        row.vehicleId(), row.remainingSeats()))))
                .toList();
        }

        List<List<Object>> delivered(Map<Long, Integer> sourceRows) {
            return deliveries.stream()
                .flatMap(delivery -> Stream.concat(
                    Stream.of(Arrays.<Object>asList(delivery.batchId(), delivery.routeVersionId(), delivery.attempt(),
                        delivery.kind(), delivery.observedAt())),
                    delivery.rows().stream().map(row -> Arrays.<Object>asList(sourceRows.get(row.observationId()),
                        row.vehicleId(), row.remainingSeats(), row.passedStopOrder(), row.crowdLevel(),
                        row.stopOrder(), row.runningState()))))
                .toList();
        }
    }
}
