package com.gustler.backend.worker.integration.observations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.springframework.test.util.ReflectionTestUtils.getField;

import com.gustler.backend.diagnostics.WorkerOperationLog;
import com.gustler.backend.diagnostics.WorkerOperationLog.Listener;
import com.gustler.backend.support.PostgresTestContainer;
import com.gustler.backend.worker.WorkerApplication;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.assertj.core.api.AutoCloseableSoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 실제 PostgreSQL 트랜잭션의 완료와 수집 계측 callback 사이의 경계를 검증한다. */
@SpringBootTest(classes = WorkerApplication.class, properties = {
    "collection.enabled=false",
    "route-geometry.enabled=false",
    "forecast.enabled=false",
    "forecast.archive.enabled=false",
    "arrivals.enabled=false"
})
@Import(PostgresTestContainer.class)
class WorkerOperationLogCommitIntegrationTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), ZoneOffset.UTC);
    private static final long OBSERVED_EPOCH_SECOND = CLOCK.instant().minusSeconds(2).getEpochSecond();
    private static final int USABLE_ROWS = 3;

    private final String sourceRoute = "commit-audit-" + UUID.randomUUID().toString().substring(0, 12);
    private final RecordingListener listener = new RecordingListener();

    private Listener previousListener;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @BeforeEach
    void registerCollectionListener() {
        previousListener = (Listener) getField(WorkerOperationLog.class, "listener");
        WorkerOperationLog.setListener(listener);
    }

    @AfterEach
    void releaseCollectionListener() {
        try {
            jdbc.sql("DELETE FROM route WHERE source_route_id = :source")
                .param("source", sourceRoute)
                .update();
        } finally {
            if (getField(WorkerOperationLog.class, "listener") == listener) {
                WorkerOperationLog.setListener(previousListener);
            }
        }
    }

    @Test
    void 실제_DB_커밋_전에는_수집_완료를_알리지_않고_커밋_후_정확히_한번_알린다() {
        // given
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        List<Integer> outcomes = new ArrayList<>();

        // when
        WorkerOperationLog.run("audit_collection_commit", sourceRoute, () ->
            transaction.executeWithoutResult(status -> {
                assertRealDatabaseTransaction();
                writeProbeRoute();
                recordTransactionOutcome(outcomes);
                WorkerOperationLog.collectionAttempted(sourceRoute, CLOCK.instant().getEpochSecond());
                WorkerOperationLog.collectionCommitted(sourceRoute, OBSERVED_EPOCH_SECOND, USABLE_ROWS);
                assertThat(listener.completions).isEmpty();
                assertThat(countStoredRoutesOnSeparateConnection()).isZero();
            }));

        // then
        assertSoftly(softly -> {
            softly.assertThat(outcomes).containsExactly(TransactionSynchronization.STATUS_COMMITTED);
            softly.assertThat(countStoredRoutesOnSeparateConnection()).isEqualTo(1);
            softly.assertThat(listener.attempts)
                .containsExactly(new Attempt(sourceRoute, CLOCK.instant().getEpochSecond()));
            softly.assertThat(listener.completions)
                .containsExactly(new Completion(sourceRoute, OBSERVED_EPOCH_SECOND, USABLE_ROWS));
        });
        assertThat(listener.operations).singleElement().satisfies(operation -> assertSoftly(softly -> {
            softly.assertThat(operation.operation()).isEqualTo("audit_collection_commit");
            softly.assertThat(operation.route()).isEqualTo(sourceRoute);
            softly.assertThat(operation.nanos()).isGreaterThanOrEqualTo(0);
            softly.assertThat(operation.failed()).isFalse();
        }));
    }

    @Test
    void 실제_DB를_롤백하면_수집_시도는_남지만_수집_완료는_알리지_않는다() {
        // given
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        List<Integer> outcomes = new ArrayList<>();

        // when
        transaction.executeWithoutResult(status -> {
            assertRealDatabaseTransaction();
            writeProbeRoute();
            recordTransactionOutcome(outcomes);
            WorkerOperationLog.collectionAttempted(sourceRoute, CLOCK.instant().getEpochSecond());
            WorkerOperationLog.collectionCommitted(sourceRoute, OBSERVED_EPOCH_SECOND, USABLE_ROWS);
            assertThat(listener.completions).isEmpty();
            assertThat(countStoredRoutesOnSeparateConnection()).isZero();
            status.setRollbackOnly();
        });

        // then
        assertSoftly(softly -> {
            softly.assertThat(outcomes).containsExactly(TransactionSynchronization.STATUS_ROLLED_BACK);
            softly.assertThat(countStoredRoutesOnSeparateConnection()).isZero();
            softly.assertThat(listener.attempts)
                .containsExactly(new Attempt(sourceRoute, CLOCK.instant().getEpochSecond()));
            softly.assertThat(listener.completions).isEmpty();
        });
    }

    private void writeProbeRoute() {
        jdbc.sql("""
            INSERT INTO route (public_route_id, source_id, source_route_id,
                display_name, start_stop_name, end_stop_name)
            VALUES (:source, 'GBIS', :source, '전달 검증', '출발', '도착') RETURNING id
            """)
            .param("source", sourceRoute)
            .query(Long.class)
            .single();
    }

    /** 바인딩된 연결을 재사용하지 않아 트랜잭션 밖의 가시성을 확인한다. */
    private long countStoredRoutesOnSeparateConnection() {
        try (Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(
                "SELECT count(*) FROM route WHERE source_route_id = ?")) {
            statement.setString(1, sourceRoute);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        } catch (SQLException failure) {
            throw new AssertionError("다른 연결에서 테스트 자료 조회 실패", failure);
        }
    }

    private void assertRealDatabaseTransaction() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(DataSourceUtils.isConnectionTransactional(connection, dataSource)).isTrue();
            softly.assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            softly.assertThat(connection.getAutoCommit()).isFalse();
            softly.assertThat(connection.isValid(1)).isTrue();
        } catch (SQLException failure) {
            throw new AssertionError("실제 테스트 DB 트랜잭션 확인 실패", failure);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private static void recordTransactionOutcome(List<Integer> outcomes) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(final int status) {
                outcomes.add(status);
            }
        });
    }

    private record Attempt(String route, long epochSecond) {
    }

    private record Completion(String route, long observedEpochSecond, int usableRows) {
    }

    private record Operation(String operation, Object route, long nanos, boolean failed) {
    }

    private static final class RecordingListener implements Listener {
        private final List<Attempt> attempts = new ArrayList<>();
        private final List<Completion> completions = new ArrayList<>();
        private final List<Operation> operations = new ArrayList<>();

        @Override
        public void collectionAttempted(String route, final long epochSecond) {
            attempts.add(new Attempt(route, epochSecond));
        }

        @Override
        public void collectionCommitted(String route, final long observedEpochSecond, final int usableRows) {
            completions.add(new Completion(route, observedEpochSecond, usableRows));
        }

        @Override
        public void completed(
            String operation,
            Object route,
            final long nanos,
            final boolean failed
        ) {
            operations.add(new Operation(operation, route, nanos, failed));
        }
    }
}
