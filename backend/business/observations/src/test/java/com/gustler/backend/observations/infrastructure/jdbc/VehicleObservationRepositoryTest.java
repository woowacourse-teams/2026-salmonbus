package com.gustler.backend.observations.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.observations.domain.CollectionPlan;
import com.gustler.backend.observations.domain.RemainingSeats;
import com.gustler.backend.observations.domain.VehicleObservation;
import com.gustler.backend.observations.domain.VehicleObservationRow;
import com.gustler.backend.observations.infrastructure.jpa.CollectionBatchRepository;
import com.gustler.backend.observations.support.ObservationIntegrationTest;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관측 저장이 INSERT 한 문장으로 끝나는지, 입력 순서와 관측 열을 보존하는지, 제약 위반을 어떤 예외로 알리는지 확인한다.
 * 모든 확인은 롤백하는 트랜잭션 안에서 해 DB 에 남기지 않는다.
 */
@ObservationIntegrationTest
class VehicleObservationRepositoryTest {

    private static final String ROUTE_3330 = "204000057";
    private static final String STOP_1 = "205000217";
    private static final String STOP_2 = "277103149";
    private static final String STOP_3 = "208000069";
    private static final String UNKNOWN_STOP = "206000564";
    private static final OffsetDateTime SCHEDULED_AT = OffsetDateTime.parse("2026-08-19T11:14:00+09:00");

    @Autowired
    private CollectionBatchRepository batches;

    @Autowired
    private VehicleObservationRepository observations;

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
    void 관측_INSERT_문장을_한_번만_낸다() {
        // when
        final int statements = inRollback(() -> {
            Batch batch = openBatch();
            countInsertStatements();
            observations.insertAll(batch.id(), batch.routeVersionId(), List.of(row(
                    0,
                    "v-0",
                    1,
                    STOP_1
            ),
                row(
                        1,
                        "v-1",
                        2,
                        STOP_2
                ), row(
                        2,
                        "v-2",
                        3,
                        STOP_3
                )));
            return insertStatementCount();
        });

        // then
        assertThat(statements).isEqualTo(1);
    }

    @Test
    void 관측이_없으면_문장을_내지_않는다() {
        // when
        final int statements = inRollback(() -> {
            Batch batch = openBatch();
            countInsertStatements();
            observations.insertAll(batch.id(), batch.routeVersionId(), List.of());
            return insertStatementCount();
        });

        // then
        assertThat(statements).isZero();
    }

    @Test
    void 입력_순서대로_관측의_열을_저장한다() {
        // when
        List<List<Object>> stored = inRollback(() -> {
            Batch batch = openBatch();
            observations.insertAll(batch.id(), batch.routeVersionId(),
                List.of(row(
                        4,
                        "v-4",
                        3,
                        STOP_3
                ), row(
                        0,
                        "v-0",
                        1,
                        STOP_1
                ), row(
                        2,
                        null,
                        2,
                        STOP_2
                )));
            return jdbc.sql("""
                SELECT vehicle_id, remaining_seats, passed_stop_order, crowd_level, stop_order, running_state
                FROM vehicle_observation WHERE observation_batch_id = ? ORDER BY id
                """).param(batch.id()).query((resultSet, rowNumber) -> Arrays.asList(
                        resultSet.getString("vehicle_id"),
                        resultSet.getObject("remaining_seats"),
                        resultSet.getObject("passed_stop_order"),
                        resultSet.getObject("crowd_level"),
                        resultSet.getObject("stop_order"),
                        resultSet.getObject("running_state")
                )).list();
        });

        // then
        assertThat(stored).containsExactly(
                Arrays.asList(
                        "v-4",
                        30,
                        3,
                        3,
                        3,
                        2
                ),
                Arrays.asList(
                        "v-0",
                        30,
                        1,
                        3,
                        1,
                        2
                ),
                Arrays.asList(
                        null,
                        30,
                        2,
                        3,
                        2,
                        2
                )
        );
    }

    @Test
    void 저장_번호는_입력_순서대로_오른다() {
        // when
        List<Integer> sourceRowsById = inRollback(() -> {
            Batch batch = openBatch();
            observations.insertAll(batch.id(), batch.routeVersionId(), List.of(row(
                    4,
                    "v-4",
                    3,
                    STOP_3
            ),
                row(
                        0,
                        "v-0",
                        1,
                        STOP_1
                ), row(
                        2,
                        "v-2",
                        2,
                        STOP_2
                )));
            return jdbc.sql("SELECT source_row_number FROM vehicle_observation WHERE observation_batch_id = ? "
                    + "ORDER BY id")
                .param(batch.id()).query(Integer.class).list();
        });

        // then
        assertThat(sourceRowsById).containsExactly(4, 0, 2);
    }

    @Test
    void 정류장_FK_위반은_제약_이름을_담은_무결성_예외로_실패한다() {
        // given
        List<VehicleObservationRow> rows = List.of(row(
                0,
                "v-0",
                1,
                STOP_1
        ), row(
                1,
                "v-1",
                1,
                UNKNOWN_STOP
        ));

        // when
        Throwable failure = failureOf(rows);

        // then
        assertSoftly(softly -> {
            softly.assertThat(failure.getClass()).isEqualTo(DataIntegrityViolationException.class);
            softly.assertThat(constraintOf(failure)).isEqualTo("fk_observation_route_stop");
            softly.assertThat(sqlStateOf(failure)).isEqualTo("23503");
        });
    }

    @Test
    void 같은_배치의_차량_중복은_제약_이름을_담은_무결성_예외로_실패한다() {
        // given
        List<VehicleObservationRow> rows = List.of(row(
                0,
                "v-0",
                1,
                STOP_1
        ), row(
                1,
                "v-0",
                2,
                STOP_2
        ));

        // when
        Rejection rejection =
            rejectionOf(rows, "ux_observation_vehicle_per_batch_date");

        // then
        assertSoftly(softly -> {
            softly.assertThat(rejection.failure().getClass()).isEqualTo(DataIntegrityViolationException.class);
            softly.assertThat(constraintOf(rejection.failure())).isEqualTo(rejection.partitionIndex());
            softly.assertThat(sqlStateOf(rejection.failure())).isEqualTo("23505");
        });
    }

    @Test
    void 상류_행_번호_중복은_제약_이름을_담은_무결성_예외로_실패한다() {
        // given
        List<VehicleObservationRow> rows = List.of(row(
                0,
                "v-0",
                1,
                STOP_1
        ), row(
                0,
                "v-1",
                2,
                STOP_2
        ));

        // when
        Rejection rejection = rejectionOf(rows, "ux_observation_source_row_date");

        // then
        assertSoftly(softly -> {
            softly.assertThat(rejection.failure().getClass()).isEqualTo(DataIntegrityViolationException.class);
            softly.assertThat(constraintOf(rejection.failure())).isEqualTo(rejection.partitionIndex());
            softly.assertThat(sqlStateOf(rejection.failure())).isEqualTo("23505");
        });
    }

    /**
     * 유일성 위반으로 거절된 저장과, 그 저장이 지켰어야 할 파티션 인덱스의 이름이다. 줄은 넣는 날(한국)의 파티션으로 가고 유일성
     * 위반은 그 파티션의 인덱스 이름으로 보고된다. 인덱스 이름은 저장과 같은 트랜잭션에서 읽는다. 트랜잭션 안에서 now() 는 변하지
     * 않으므로 줄이 기본값으로 받는 source_date 와 같은 날짜의 파티션을 가리킨다.
     */
    private Rejection rejectionOf(
        List<VehicleObservationRow> rows,
        String uniqueIndex
    ) {
        return inRollback(() -> {
            Batch batch = openBatch();
            String partitionIndex = partitionIndexOfTransactionDate(uniqueIndex);
            Throwable failure = catchThrowable(() -> observations.insertAll(batch.id(), batch.routeVersionId(), rows));
            return new Rejection(failure, partitionIndex);
        });
    }

    /** 관측 테이블의 유일 인덱스에 딸린, 지금 트랜잭션이 시작한 한국 날짜 파티션의 인덱스 이름이다. */
    private String partitionIndexOfTransactionDate(String uniqueIndex) {
        return jdbc.sql("""
            SELECT child.relname
            FROM pg_inherits link
            JOIN pg_class child ON child.oid = link.inhrelid
            JOIN pg_index child_index ON child_index.indexrelid = child.oid
            WHERE link.inhparent = to_regclass(?)
              AND child_index.indrelid = to_regclass(
                  'vehicle_observation_p' || to_char((now() AT TIME ZONE 'Asia/Seoul')::date, 'YYYYMMDD'))
            """).param(uniqueIndex).query(String.class).single();
    }

    private Throwable failureOf(List<VehicleObservationRow> rows) {
        return inRollback(() -> {
            Batch batch = openBatch();
            return catchThrowable(() -> observations.insertAll(batch.id(), batch.routeVersionId(), rows));
        });
    }

    private <T> T inRollback(Supplier<T> work) {
        return rollbackOnly.execute(status -> {
            status.setRollbackOnly();
            return work.get();
        });
    }

    private Batch openBatch() {
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
            """).params(
                    routeVersionId,
                    STOP_1,
                    routeVersionId,
                    STOP_2,
                    routeVersionId,
                    STOP_3
            ).update();
        final long batchId = batches.prepare(
            new CollectionPlan(routeVersionId, SCHEDULED_AT, "bulk-insert"), true).batchId();
        return new Batch(batchId, routeVersionId);
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

    private static VehicleObservationRow row(
        final int sourceRow,
        String vehicleId,
        final int stopOrder,
        String stopId
    ) {
        return new VehicleObservationRow(sourceRow, new VehicleObservation(
                vehicleId,
                "경기70아0001",
                stopOrder,
                stopId,
                2,
                RemainingSeats.from(30),
                3,
                0,
                11,
                1
        ));
    }

    private static String constraintOf(Throwable failure) {
        return firstInChain(failure, VehicleObservationRepositoryTest::constraintNameOf);
    }

    private static String constraintNameOf(Throwable cause) {
        if (!(cause instanceof PSQLException postgres)) {
            return null;
        }
        if (postgres.getServerErrorMessage() == null) {
            return null;
        }
        return postgres.getServerErrorMessage().getConstraint();
    }

    private static String sqlStateOf(Throwable failure) {
        return firstInChain(failure, VehicleObservationRepositoryTest::postgresSqlStateOf);
    }

    private static String postgresSqlStateOf(Throwable cause) {
        if (cause instanceof PSQLException postgres) {
            return postgres.getSQLState();
        }
        return null;
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

    private record Batch(long id, long routeVersionId) {

    }

    private record Rejection(Throwable failure, String partitionIndex) {
    }
}
