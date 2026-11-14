package com.gustler.backend.observations.infrastructure.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.gustler.backend.observations.domain.CollectionBatch;
import com.gustler.backend.observations.domain.CollectionBatchRepository;
import com.gustler.backend.observations.domain.CollectionPlan;
import com.gustler.backend.observations.domain.RemainingSeats;
import com.gustler.backend.observations.domain.StoredObservations.Row;
import com.gustler.backend.observations.domain.UpstreamObservationRow;
import com.gustler.backend.observations.domain.VehicleObservation;
import com.gustler.backend.observations.support.ObservationIntegrationTest;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관측 한 문장 저장이 JPA 저장과 같은 문장 경계·예외를 갖는지 확인한다.
 * 모든 확인은 롤백하는 트랜잭션 안에서 해 DB 에 남기지 않는다.
 */
@ObservationIntegrationTest
class VehicleObservationBulkInsertTest {
    private static final String ROUTE_3330 = "204000057";
    private static final String STOP_1 = "205000217";
    private static final String STOP_2 = "277103149";
    private static final String STOP_3 = "208000069";
    private static final String UNKNOWN_STOP = "206000564";
    private static final OffsetDateTime SCHEDULED_AT = OffsetDateTime.parse("2026-08-19T11:14:00+09:00");

    @Autowired
    private ObservationBatchEntityRepository batchEntities;

    @Autowired
    private VehicleObservationEntityRepository observationEntities;

    @Autowired
    private VehicleObservationBulkInsert bulkInsert;

    @Autowired
    private CollectionBatchRepository batches;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate rollbackOnly;

    @BeforeEach
    void 롤백_전용_트랜잭션을_준비한다() {
        rollbackOnly = new TransactionTemplate(transactionManager);
    }

    @Test
    void 켜짐_저장은_관측_INSERT_문장을_한_번만_낸다() {
        // when
        final int statements = inRollback(() -> {
            CollectionBatch batch = openBatch();
            countInsertStatements();
            repository(true).saveObservations(batch, List.of(row(0, "v-0", 1, STOP_1),
                row(1, "v-1", 2, STOP_2), row(2, "v-2", 3, STOP_3)));
            return insertStatementCount();
        });

        // then
        assertThat(statements).isEqualTo(1);
    }

    @Test
    void 꺼짐_저장은_관측_한_줄마다_INSERT_문장을_낸다() {
        // when
        final int statements = inRollback(() -> {
            CollectionBatch batch = openBatch();
            countInsertStatements();
            repository(false).saveObservations(batch, List.of(row(0, "v-0", 1, STOP_1),
                row(1, "v-1", 2, STOP_2), row(2, "v-2", 3, STOP_3)));
            return insertStatementCount();
        });

        // then
        assertThat(statements).isEqualTo(3);
    }

    @Test
    void 켜짐_저장은_관측이_없으면_문장을_내지_않는다() {
        // when
        final int statements = inRollback(() -> {
            CollectionBatch batch = openBatch();
            countInsertStatements();
            assertThat(repository(true).saveObservations(batch, List.of())).isEmpty();
            return insertStatementCount();
        });

        // then
        assertThat(statements).isZero();
    }

    @Test
    void 켜짐_저장은_입력_순서대로_같은_상류_행의_저장_번호를_붙여_반환한다() {
        // when
        List<List<Row>> returnedAndStored = inRollback(() -> {
            CollectionBatch batch = openBatch();
            List<Row> returned = repository(true).saveObservations(batch, List.of(row(4, "v-4", 3, STOP_3),
                row(0, "v-0", 1, STOP_1), row(2, null, 2, STOP_2)));
            return List.of(returned, returned.stream().map(row -> storedRowOf(row.observationId())).toList());
        });

        // then
        assertThat(returnedAndStored.get(0)).extracting(Row::vehicleId).containsExactly("v-4", "v-0", null);
        assertThat(returnedAndStored.get(0)).containsExactlyElementsOf(returnedAndStored.get(1));
    }

    @Test
    void 켜짐_저장의_번호는_입력_순서대로_오른다() {
        // when
        List<Integer> sourceRowsById = inRollback(() -> {
            CollectionBatch batch = openBatch();
            repository(true).saveObservations(batch, List.of(row(4, "v-4", 3, STOP_3),
                row(0, "v-0", 1, STOP_1), row(2, "v-2", 2, STOP_2)));
            return jdbc.sql("SELECT source_row_number FROM vehicle_observation WHERE observation_batch_id = ? "
                    + "ORDER BY id")
                .param(batch.id()).query(Integer.class).list();
        });

        // then
        assertThat(sourceRowsById).containsExactly(4, 0, 2);
    }

    @Test
    void 정류장_FK_위반은_꺼짐과_같은_예외와_제약_이름으로_실패한다() {
        // given
        List<UpstreamObservationRow> rows = List.of(row(0, "v-0", 1, STOP_1), row(1, "v-1", 1, UNKNOWN_STOP));

        // when
        Throwable jpa = failureOf(false, rows);
        Throwable bulk = failureOf(true, rows);

        // then
        assertThat(jpa.getClass()).isEqualTo(bulk.getClass());
        assertThat(constraintOf(bulk)).isEqualTo(constraintOf(jpa)).isEqualTo("fk_observation_route_stop");
        assertThat(sqlStateOf(bulk)).isEqualTo(sqlStateOf(jpa)).isEqualTo("23503");
    }

    @Test
    void 같은_배치의_차량_중복은_꺼짐과_같은_예외와_제약_이름으로_실패한다() {
        // given
        List<UpstreamObservationRow> rows = List.of(row(0, "v-0", 1, STOP_1), row(1, "v-0", 2, STOP_2));

        // when
        Throwable jpa = failureOf(false, rows);
        Throwable bulk = failureOf(true, rows);

        // then
        assertThat(jpa.getClass()).isEqualTo(bulk.getClass());
        assertThat(constraintOf(bulk)).isEqualTo(constraintOf(jpa)).isEqualTo("ux_observation_vehicle_per_batch");
        assertThat(sqlStateOf(bulk)).isEqualTo(sqlStateOf(jpa)).isEqualTo("23505");
    }

    @Test
    void 상류_행_번호_중복은_꺼짐과_같은_예외와_제약_이름으로_실패한다() {
        // given
        List<UpstreamObservationRow> rows = List.of(row(0, "v-0", 1, STOP_1), row(0, "v-1", 2, STOP_2));

        // when
        Throwable jpa = failureOf(false, rows);
        Throwable bulk = failureOf(true, rows);

        // then
        assertThat(jpa.getClass()).isEqualTo(bulk.getClass());
        assertThat(constraintOf(bulk)).isEqualTo(constraintOf(jpa)).isEqualTo("ux_observation_source_row");
        assertThat(sqlStateOf(bulk)).isEqualTo(sqlStateOf(jpa)).isEqualTo("23505");
    }

    private Throwable failureOf(final boolean bulk, List<UpstreamObservationRow> rows) {
        return inRollback(() -> {
            CollectionBatch batch = openBatch();
            return catchThrowable(() -> repository(bulk).saveObservations(batch, rows));
        });
    }

    private JpaCollectionBatchRepository repository(final boolean bulk) {
        return new JpaCollectionBatchRepository(batchEntities, observationEntities, bulkInsert, bulk);
    }

    private <T> T inRollback(Supplier<T> work) {
        return rollbackOnly.execute(status -> {
            status.setRollbackOnly();
            return work.get();
        });
    }

    private CollectionBatch openBatch() {
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
        final long batchId = batches.save(CollectionBatch.start(
            new CollectionPlan(routeVersionId, SCHEDULED_AT, "bulk-insert"), true));
        return batches.getById(batchId);
    }

    /** 문장 단위 트리거로 관측 INSERT 문장 수를 센다. 트랜잭션과 함께 롤백된다. */
    private void countInsertStatements() {
        jdbc.sql("CREATE TABLE test_observation_insert_statement (fired integer NOT NULL)").update();
        jdbc.sql("""
            CREATE FUNCTION test_count_observation_insert() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
                INSERT INTO test_observation_insert_statement VALUES (1);
                RETURN NULL;
            END $$
            """).update();
        jdbc.sql("""
            CREATE TRIGGER test_observation_insert_statement AFTER INSERT ON vehicle_observation
                FOR EACH STATEMENT EXECUTE FUNCTION test_count_observation_insert()
            """).update();
    }

    private int insertStatementCount() {
        return jdbc.sql("SELECT count(*) FROM test_observation_insert_statement").query(Integer.class).single();
    }

    private Row storedRowOf(final long observationId) {
        return jdbc.sql("""
            SELECT id, vehicle_id, remaining_seats, passed_stop_order, crowd_level, stop_order, running_state
            FROM vehicle_observation WHERE id = ?
            """).param(observationId).query((resultSet, rowNumber) -> new Row(resultSet.getLong("id"),
                resultSet.getString("vehicle_id"), resultSet.getObject("remaining_seats", Integer.class),
                resultSet.getObject("passed_stop_order", Integer.class),
                resultSet.getObject("crowd_level", Integer.class), resultSet.getObject("stop_order", Integer.class),
                resultSet.getObject("running_state", Integer.class)))
            .single();
    }

    private static UpstreamObservationRow row(final int sourceRow, String vehicleId, final int stopOrder,
        String stopId) {
        return new UpstreamObservationRow(sourceRow, new VehicleObservation(vehicleId, "경기70아0001", stopOrder,
            stopId, 2, RemainingSeats.from(30), 3, 0, 11, 1));
    }

    private static String constraintOf(Throwable failure) {
        return firstInChain(failure, cause -> cause instanceof PSQLException postgres
            && postgres.getServerErrorMessage() != null ? postgres.getServerErrorMessage().getConstraint() : null);
    }

    private static String sqlStateOf(Throwable failure) {
        return firstInChain(failure, cause -> cause instanceof PSQLException postgres ? postgres.getSQLState() : null);
    }

    private static String firstInChain(Throwable failure, Function<Throwable, String> read) {
        ArrayDeque<Throwable> remaining = new ArrayDeque<>(List.of(failure));
        while (!remaining.isEmpty()) {
            Throwable current = remaining.removeFirst();
            String value = read.apply(current);
            if (value != null) {
                return value;
            }
            if (current.getCause() != null && current.getCause() != current) {
                remaining.add(current.getCause());
            }
        }
        return null;
    }
}
