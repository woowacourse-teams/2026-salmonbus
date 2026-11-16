package com.gustler.backend.worker.integration.observations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.gbis.api.GbisLocationResult;
import com.gustler.backend.gbis.api.dto.BusLocationResponse;
import com.gustler.backend.observations.api.CollectionQualityHook;
import com.gustler.backend.observations.api.ObservationDeliveryBatch;
import com.gustler.backend.observations.api.ObservationDeliveryHook;
import com.gustler.backend.observations.api.VehicleObservationsStored;
import com.gustler.backend.observations.application.ObservationBatchLedger;
import com.gustler.backend.observations.application.ObservationLoader;
import com.gustler.backend.observations.domain.CollectionBatchRepository;
import com.gustler.backend.observations.domain.CollectionPlan;
import com.gustler.backend.observations.infrastructure.gbis.GbisObservationMapper;
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
 * 수집 응답을 저장했을 때 남는 관측 줄, 배치 결과, 정원 기록, 좌석 품질 훅과 전달 훅의 입력이 고정 기대값과 같은지 확인한다
 * ({@link ObservationLoadBaseline}).
 *
 * <p>저장은 롤백하는 트랜잭션으로 돌려 DB 에 남기지 않는다. 좌석 품질 훅은 실제 구성의 것을 쓴다.
 */
@SpringBootTest(classes = WorkerApplication.class)
@Import({ PostgresTestContainer.class, CollectionRuntimeConfiguration.class })
class ObservationLoaderBaselineTest {
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
    private static final String BATCH = "batch";
    private static final String ROUTE_VERSION = "routeVersion";

    @Autowired
    private ObservationBatchLedger ledger;

    @Autowired
    private CollectionBatchRepository batches;

    @Autowired
    private List<CollectionQualityHook> qualityHooks;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private long routeVersionId;
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
        routeVersionId = jdbc.sql("""
            INSERT INTO route_version (route_id, content_digest, valid_from, turn_sequence)
            VALUES (?, ?, ?, 2) RETURNING id
            """).params(routeId, "0".repeat(64), SCHEDULED_AT).query(Long.class).single();
        jdbc.sql("""
            INSERT INTO route_stop (route_version_id, stop_order, stop_id, name, direction, boarding_allowed)
            VALUES (?, 1, ?, '범계역', 'UP', true), (?, 2, ?, '안양대교(경유)', 'UP', false),
                   (?, 3, ?, '안양역', 'DOWN', true)
            """).params(routeVersionId, STOP_1, routeVersionId, STOP_2, routeVersionId, STOP_3).update();
        batchId = ledger.reserve(new CollectionPlan(routeVersionId, SCHEDULED_AT, "load-baseline"), RESERVED_AT)
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
    void 저장한_관측과_배치_결과와_훅_입력은_기대값과_같다(String scenario, List<BusLocationResponse.BusLocation> buses) {
        // given
        List<String> expected = ObservationLoadBaseline.read().linesOf(scenario);

        // when
        Outcome stored = storeAndRollBack(buses);

        // then
        assertThat(stored.lines()).containsExactlyElementsOf(expected);
    }

    @Test
    void 좌석_품질_훅이_가린_좌석은_전달되지_않고_정원은_올라간다() {
        // given
        List<BusLocationResponse.BusLocation> buses = List.of(
            bus("eq-1", "경기70아0001", NORMAL_BUS, STOP_1, 1, DEPARTED, 71, 3),
            bus("eq-2", "경기70아0002", NORMAL_BUS, STOP_2, 2, DEPARTED, 43, 3));

        // when
        Outcome stored = storeAndRollBack(buses);

        // then
        assertSoftly(softly -> {
            softly.assertThat(stored.delivered()).contains(Arrays.asList(0, "eq-1", null, 1, 3, 1, DEPARTED),
                Arrays.asList(1, "eq-2", 43, 2, 3, 2, DEPARTED));
            softly.assertThat(stored.observations().getFirst()).contains("\"seat_quality_status\": \"REVIEW\"",
                "\"reported_remaining_seats\": 71");
            softly.assertThat(stored.capacities()).singleElement().asString().contains("\"vehicle_id\": \"eq-2\"",
                "\"capacity\": 43", "\"evidence_source_row\": 1");
        });
    }

    @Test
    void 저장한_관측은_호출자의_트랜잭션과_함께_롤백된다() {
        // when
        Outcome stored = storeAndRollBack(List.of(bus("eq-1", "경기70아0001", NORMAL_BUS, STOP_1, 1, DEPARTED, 30, 3)));

        // then
        assertSoftly(softly -> {
            softly.assertThat(stored.observations()).hasSize(1);
            softly.assertThat(jdbc.sql("SELECT count(*) FROM vehicle_observation WHERE observation_batch_id = ?")
                .param(batchId).query(Integer.class).single()).isZero();
        });
    }

    private Outcome storeAndRollBack(List<BusLocationResponse.BusLocation> buses) {
        RecordingHooks recording = new RecordingHooks();
        List<CollectionQualityHook> hooks = new ArrayList<>(qualityHooks);
        hooks.add(recording);
        ObservationLoader loader = new ObservationLoader(batches, hooks, List.of(recording));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        return transaction.execute(status -> {
            status.setRollbackOnly();
            loader.load(batchId, GbisObservationMapper.from(new GbisLocationResult.Success(QUERY_TIME, buses)),
                GbisObservationMapper.collect(buses), RESPONSE_RECEIVED_AT);
            readTimesInUtc();
            StoredIds ids = new StoredIds(batchId, routeVersionId, sourceRowsById());
            return new Outcome(observations(), batch(), capacities(), recording.stored(ids),
                recording.delivered(ids));
        });
    }

    /** PostgreSQL 은 jsonb 의 시각을 세션 시간대로 적는다. 이 트랜잭션에서만 UTC 로 맞춰 JVM 시간대와 상관없이 같은 글자를 읽는다. */
    private void readTimesInUtc() {
        jdbc.sql("SET LOCAL TIME ZONE 'UTC'").update();
    }

    private List<String> observations() {
        return jdbc.sql("""
            SELECT ((to_jsonb(observation) - 'id' - 'source_date') || jsonb_build_object(
                'observation_batch_id', CASE observation.observation_batch_id
                    WHEN :batchId THEN CAST(:batch AS text)
                    ELSE CAST(observation.observation_batch_id AS text) END,
                'route_version_id', CASE observation.route_version_id
                    WHEN :routeVersionId THEN CAST(:routeVersion AS text)
                    ELSE CAST(observation.route_version_id AS text) END))::text
            FROM vehicle_observation observation
            WHERE observation.observation_batch_id = :batchId ORDER BY observation.source_row_number
            """).params(idLabels()).query(String.class).list();
    }

    private String batch() {
        return jdbc.sql("""
            SELECT ((to_jsonb(batch) - 'source_date') || jsonb_build_object(
                'id', CAST(:batch AS text),
                'route_version_id', CASE batch.route_version_id
                    WHEN :routeVersionId THEN CAST(:routeVersion AS text)
                    ELSE CAST(batch.route_version_id AS text) END))::text
            FROM observation_batch batch WHERE batch.id = :batchId
            """).params(idLabels()).query(String.class).single();
    }

    private List<String> capacities() {
        return jdbc.sql("""
            SELECT ((to_jsonb(saved) - 'updated_at' - 'evidence_observation_id') || jsonb_build_object(
                'evidence_batch_id', CASE saved.evidence_batch_id
                    WHEN :batchId THEN CAST(:batch AS text)
                    ELSE CAST(saved.evidence_batch_id AS text) END,
                'evidence_source_row', observation.source_row_number))::text
            FROM vehicle_capacity saved
            LEFT JOIN vehicle_observation observation ON observation.id = saved.evidence_observation_id
            WHERE saved.vehicle_id LIKE 'eq-%' ORDER BY saved.vehicle_id
            """).params(idLabels()).query(String.class).list();
    }

    private Map<String, Object> idLabels() {
        return Map.of("batchId", batchId, "batch", BATCH, "routeVersionId", routeVersionId,
            "routeVersion", ROUTE_VERSION);
    }

    private Map<Long, Integer> sourceRowsById() {
        return jdbc.sql("SELECT id, source_row_number FROM vehicle_observation WHERE observation_batch_id = ?")
            .param(batchId)
            .query((resultSet, rowNumber) -> Map.entry(
                resultSet.getLong("id"),
                resultSet.getInt("source_row_number")))
            .list()
            .stream()
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static BusLocationResponse.BusLocation bus(
        String vehicleId,
        String plateNumber,
        final int vehicleType,
        String stopId,
        Integer stopOrder,
        final int runningState,
        Integer remainingSeats,
        Integer crowdLevel
    ) {
        return new BusLocationResponse.BusLocation(plateNumber, vehicleId, vehicleType, ROUTE_3330, 11, stopId,
            stopOrder, runningState, remainingSeats, crowdLevel, 1);
    }

    private record Outcome(
        List<String> observations,
        String batch,
        List<String> capacities,
        List<List<Object>> stored,
        List<List<Object>> delivered
    ) {
        /** {@link ObservationLoadBaseline}의 줄 형식과 순서로 적는다. */
        List<String> lines() {
            List<String> lines = new ArrayList<>();
            observations.forEach(observation -> lines.add("observation " + observation));
            lines.add("batch " + batch);
            capacities.forEach(capacity -> lines.add("capacity " + capacity));
            stored.forEach(values -> lines.add("stored " + cells(values)));
            delivered.forEach(values -> lines.add("delivered " + cells(values)));
            return lines;
        }

        private static String cells(List<Object> values) {
            return values.stream().map(Outcome::cell).collect(Collectors.joining(" "));
        }

        private static String cell(Object value) {
            if (value instanceof String string) {
                return "\"" + string + "\"";
            }
            return String.valueOf(value);
        }
    }

    /** DB 가 매긴 번호를 실행마다 같은 값으로 바꾼다. 준비한 것과 다른 번호는 그대로 둬 기대값과 어긋나게 한다. */
    private record StoredIds(long batchId, long routeVersionId, Map<Long, Integer> sourceRows) {
        Object batch(final long id) {
            if (id == batchId) {
                return BATCH;
            }
            return id;
        }

        Object routeVersion(final long id) {
            if (id == routeVersionId) {
                return ROUTE_VERSION;
            }
            return id;
        }

        Integer sourceRow(final long observationId) {
            return sourceRows.get(observationId);
        }
    }

    /** 품질 훅과 전달 훅이 받은 값을 남긴다. 좌석을 가리지 않는다. */
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

        List<List<Object>> stored(StoredIds ids) {
            return storedEvents.stream()
                .flatMap(event -> Stream.concat(
                    Stream.of(Arrays.<Object>asList(ids.batch(event.batchId()),
                        ids.routeVersion(event.routeVersionId()), event.observedAt())),
                    event.rows().stream().map(row -> Arrays.<Object>asList(ids.sourceRow(row.observationId()),
                        row.vehicleId(), row.remainingSeats()))))
                .toList();
        }

        List<List<Object>> delivered(StoredIds ids) {
            return deliveries.stream()
                .flatMap(delivery -> Stream.concat(
                    Stream.of(Arrays.<Object>asList(ids.batch(delivery.batchId()),
                        ids.routeVersion(delivery.routeVersionId()), delivery.attempt(), delivery.kind(),
                        delivery.observedAt())),
                    delivery.rows().stream().map(row -> Arrays.<Object>asList(ids.sourceRow(row.observationId()),
                        row.vehicleId(), row.remainingSeats(), row.passedStopOrder(), row.crowdLevel(),
                        row.stopOrder(), row.runningState()))))
                .toList();
        }
    }
}
