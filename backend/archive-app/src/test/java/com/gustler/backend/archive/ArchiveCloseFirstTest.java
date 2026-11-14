package com.gustler.backend.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.domain.publication.ForecastPublication;
import com.gustler.backend.forecasting.infrastructure.jdbc.JdbcAlightingAwareForecastEvaluation;
import com.gustler.backend.forecasting.infrastructure.jdbc.JdbcForecastPublicationRepository;
import com.gustler.backend.forecasting.infrastructure.jdbc.JdbcRouteWorkAccess;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 경계를 먼저 닫고 최근 기록을 노선 잠금 밖에서 한 번 확인하는 이관 순서를 실제 PG 에서 검증한다.
 *
 * <p>닫은 뒤 다시 확인하지 않는 근거는 닫는 트랜잭션과 예보 발행·하차 정산 저장이 같은 노선 행 잠금으로 줄을 선다는 것이다. 이 근거를 깨는
 * 경우(남은 일이 있는데 닫기, 닫힌 날짜에 발행하기, 잠금을 쥔 커밋과 닫기가 엇갈리기)가 막히는지 함께 본다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ArchiveCloseFirstTest {
    private static final String DAY = "2026-11-07T03:00Z";
    private static final Instant DAY_END = Instant.parse("2026-11-07T15:00:00Z");

    OperationalArchiveIntegrationTest fixture;
    JdbcClient jdbc;
    DriverManagerDataSource source;
    PlatformTransactionManager manager;
    ArchiveRepository repository;

    @BeforeAll
    void migrate() {
        fixture = new OperationalArchiveIntegrationTest();
        fixture.migrate();
        source = new DriverManagerDataSource(fixture.database.jdbcUrl(), fixture.database.username(),
            fixture.database.password());
        jdbc = JdbcClient.create(source);
        manager = new JdbcTransactionManager(source);
        repository = new ArchiveRepository(jdbc, manager);
    }

    @AfterAll
    void close() {
        fixture.close();
    }

    @BeforeEach
    void setup() {
        jdbc.sql("TRUNCATE operational_archive_boundary").update();
        fixture.setup();
    }

    @Test
    void 남은_일이_있으면_경계를_닫지_않고_60초_뒤에_다시_본다() {
        // given
        final long id = fixture.observation(fixture.batch(DAY));
        fixture.forecast(id, false, 0);
        settlementWork(jdbc, id);
        ArchiveRepository.Job job = claim();

        // when
        String reason = repository.closeRange(job);
        readyAgain();
        service(new Objects(), true).advance();

        // then
        assertSoftly(softly -> {
            softly.assertThat(reason).isEqualTo("ALIGHTING_AWARE_SETTLEMENT_PENDING");
            softly.assertThat(boundary()).isNull();
            softly.assertThat(jobValue("failure_code")).isEqualTo("ALIGHTING_AWARE_SETTLEMENT_PENDING");
            softly.assertThat(jdbc.sql("""
                SELECT retry_at BETWEEN clock_timestamp()+interval '50 seconds' AND clock_timestamp()+interval '70 seconds'
                FROM operational_archive_job
                """).query(Boolean.class).single()).isTrue();
            softly.assertThat(fixture.count("vehicle_observation")).isOne();
        });
    }

    @Test
    void 닫은_날짜에는_예보를_다시_발행할_수_없고_정산_대기가_없으면_하차_평가도_생기지_않는다() {
        // given
        final long batch = fixture.batch(DAY);
        fixture.observation(batch);
        ArchiveRepository.Job job = claim();
        assertThat(repository.closeRange(job)).isEqualTo("READY");
        final long model = model();
        JdbcForecastPublicationRepository publications = new JdbcForecastPublicationRepository(jdbc,
            new JdbcTemplate(source));
        ForecastPublication publication = new ForecastPublication(batch, fixture.version, model, 1, 1,
            Instant.parse(DAY.replace("Z", ":00Z")), Instant.parse("2026-11-09T03:00:00Z"),
            Instant.parse("2026-11-09T03:00:00Z"), List.of());

        // when & then
        assertThatThrownBy(() -> new TransactionTemplate(manager).execute(status -> publications.save(publication)))
            .isInstanceOf(IllegalStateException.class);
        new JdbcAlightingAwareForecastEvaluation(jdbc, new JdbcRouteWorkAccess(jdbc), fixture.clock, manager).settle();
        assertSoftly(softly -> {
            softly.assertThat(boundary()).isEqualTo(DAY_END);
            softly.assertThat(fixture.count("forecast_publication")).isZero();
            softly.assertThat(fixture.count("alighting_forecast_evaluation_work")).isZero();
            softly.assertThat(fixture.count("alighting_forecast_evaluation_result")).isZero();
        });
    }

    @Test
    void 노선_잠금을_쥔_예보_커밋과_닫기가_엇갈리면_닫기는_기다렸다가_새_정산_대기를_보고_닫지_않는다() throws Exception {
        // given
        final long id = fixture.observation(fixture.batch(DAY));
        fixture.forecast(id, false, 0);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        try (RouteLock committer = RouteLock.hold(fixture, fixture.version)) {
            committer.execute("""
                INSERT INTO alighting_forecast_evaluation_work(vehicle_observation_id,target_stop_order,target_kind,
                    label_version,route_version_id) VALUES(?,2,'AFTER_ALIGHTING_ESTIMATE','label-v1',?)
                """, id, fixture.version);
            CompletableFuture<Void> commit = committer.commitAfter(Duration.ofMillis(600));

            // when
            service(new Objects(), true, metrics, Duration.ZERO, Duration.ofMillis(300)).advance();
            commit.get(5, TimeUnit.SECONDS);
        }

        // then
        assertSoftly(softly -> {
            softly.assertThat(retries(metrics, "close")).isPositive();
            softly.assertThat(jobValue("failure_code")).isEqualTo("ALIGHTING_AWARE_SETTLEMENT_PENDING");
            softly.assertThat(boundary()).isNull();
            softly.assertThat(fixture.count("seat_forecast")).isOne();
        });
    }

    @Test
    void 최근_기록_확인과_내보내기는_노선_잠금_밖에서_돌고_닫기만_노선_잠금을_기다린다() throws Exception {
        // given
        final long id = fixture.observation(fixture.batch(DAY));
        fixture.forecast(id, false, 0);
        ArchiveRepository.Job job = claim();
        assertThat(repository.closeRange(job)).isEqualTo("READY");

        try (RouteLock worker = RouteLock.hold(fixture, fixture.version)) {
            // when
            String recent = repository.recentRecords(job);
            ArchiveRepository.Page page = repository.page(job, ArchiveTable.OBSERVATION, "[]", 10);

            // then
            assertThat(recent).isEqualTo("READY");
            assertThat(page.rows()).hasSize(1);
            assertThatThrownBy(() -> repository.closeRange(job))
                .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("55P03"));
        }
    }

    @Test
    void 이관_한_번에서_노선_잠금은_닫기와_삭제_구간에만_잡힌다() {
        // given
        final long id = fixture.observation(fixture.batch(DAY));
        fixture.forecast(id, false, 0);
        Objects objects = new Objects();
        objects.onExport = () -> objects.routeFreeDuringExport = routeLockAvailable();

        // when
        service(objects, true).advance();

        // then
        assertSoftly(softly -> {
            softly.assertThat(objects.routeFreeDuringExport).isTrue();
            softly.assertThat(jobValue("state")).isEqualTo("DONE");
            softly.assertThat(fixture.count("vehicle_observation")).isZero();
        });
    }

    @Test
    void 최근_기록으로_보류하면_경계는_닫아_두고_다음_한국_자정에_다시_시도한다() {
        // given
        final long id = fixture.observation(fixture.batch(DAY));
        fixture.forecast(id, false, 0);
        jdbc.sql("UPDATE forecast_evaluation_result SET scored_at='2026-11-08T03:00Z'").update();

        // when
        service(new Objects(), true).advance();

        // then
        assertSoftly(softly -> {
            softly.assertThat(jobValue("failure_code")).isEqualTo("RECENT_CHILD_RECORD");
            softly.assertThat(jobValue("state")).isEqualTo("EXPORTING");
            softly.assertThat(jdbc.sql("SELECT retry_at FROM operational_archive_job").query(OffsetDateTime.class)
                .single().toInstant()).isEqualTo(Instant.parse("2026-11-09T15:00:00Z"));
            softly.assertThat(boundary()).isEqualTo(DAY_END);
            softly.assertThat(fixture.count("operational_archive_file")).isZero();
            softly.assertThat(fixture.count("forecast_evaluation_result")).isOne();
        });
    }

    @Test
    void 삭제_도중_노선_잠금을_못_얻으면_같은_파일을_다시_시도한다() {
        // given
        final long id = fixture.observation(fixture.batch(DAY));
        fixture.forecast(id, false, 0);
        Objects objects = new Objects();
        service(objects, false).advance();
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        RouteLock[] worker = new RouteLock[1];
        objects.onPurgeRead = () -> {
            worker[0] = RouteLock.hold(fixture, fixture.version);
            worker[0].commitAfter(Duration.ofMillis(400));
        };
        readyAgain();

        // when
        try {
            service(objects, true, metrics, Duration.ZERO, Duration.ofMillis(300)).advance();
        } finally {
            worker[0].close();
        }

        // then
        assertSoftly(softly -> {
            softly.assertThat(retries(metrics, "purge")).isPositive();
            softly.assertThat(jobValue("state")).isEqualTo("DONE");
            softly.assertThat(fixture.count("seat_forecast")).isZero();
            softly.assertThat(fixture.count("vehicle_observation")).isZero();
        });
    }

    @Test
    void 노선_잠금을_끝내_못_얻으면_세_번_다시_시도한_뒤_실패로_남기고_지우지_않는다() {
        // given
        final long id = fixture.observation(fixture.batch(DAY));
        fixture.forecast(id, false, 0);
        Objects objects = new Objects();
        service(objects, false).advance();
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        RouteLock[] worker = new RouteLock[1];
        objects.onPurgeRead = () -> worker[0] = RouteLock.hold(fixture, fixture.version);
        readyAgain();

        // when
        try {
            service(objects, true, metrics, Duration.ZERO, Duration.ofMillis(50)).advance();
        } finally {
            worker[0].close();
        }

        // then
        assertSoftly(softly -> {
            softly.assertThat(retries(metrics, "purge")).isEqualTo(3);
            softly.assertThat(jobValue("state")).isEqualTo("VERIFIED");
            softly.assertThat(jobValue("failure_code")).isNotNull().isNotEqualTo("DELETE_DISABLED");
            softly.assertThat(jdbc.sql("SELECT count(*) FROM operational_archive_file WHERE purged")
                .query(Long.class).single()).isZero();
            softly.assertThat(fixture.count("forecast_evaluation_result")).isOne();
        });
    }

    @Test
    void 내보내기를_끝낸_작업은_최근_기록_확인_없이_삭제를_이어_간다() {
        // given
        final long id = fixture.observation(fixture.batch(DAY));
        fixture.forecast(id, false, 0);
        Objects objects = new Objects();
        service(objects, false).advance();
        assertThat(jobValue("state")).isEqualTo("VERIFIED");
        ArchiveRepository noRecentCheck = new ArchiveRepository(jdbc, manager) {
            @Override
            public String recentRecords(Job job) {
                throw new AssertionError("내보낸 작업에서 최근 기록을 다시 확인했다");
            }
        };
        readyAgain();

        // when
        new ArchiveService(noRecentCheck, objects, fixture.clock, new SimpleMeterRegistry(),
            "salmonbus/test/lake-v2/", 2, true).advance();

        // then
        assertSoftly(softly -> {
            softly.assertThat(jobValue("state")).isEqualTo("DONE");
            softly.assertThat(fixture.count("seat_forecast")).isZero();
            softly.assertThat(fixture.count("observation_batch")).isZero();
        });
    }

    @Test
    void 삭제_도중_하차_이력_보존_조건이_생기면_지우지_않고_멈춘다() {
        // given
        final long batch = fixture.batch(DAY);
        final long id = fixture.observation(batch);
        fixture.forecast(id, false, 0);
        Objects objects = new Objects();
        service(objects, false).advance();
        objects.onPurgeRead = () -> jdbc.sql("""
            INSERT INTO alighting_history_checkpoint VALUES(?,'2026-11-07T03:00:01Z',?,'2026-11-07T02:58:31Z')
            """).params(fixture.version, batch).update();
        readyAgain();

        // when
        service(objects, true).advance();

        // then
        assertSoftly(softly -> {
            softly.assertThat(jobValue("failure_code")).isEqualTo("ALIGHTING_HISTORY_REQUIRED");
            softly.assertThat(jobValue("state")).isEqualTo("VERIFIED");
            softly.assertThat(jdbc.sql("SELECT count(*) FROM operational_archive_file WHERE purged")
                .query(Long.class).single()).isZero();
            softly.assertThat(fixture.count("forecast_evaluation_result")).isOne();
            softly.assertThat(fixture.count("vehicle_observation")).isOne();
        });
    }

    @Test
    void 이관_경계가_닫혀_있지_않으면_검증한_파일도_지우지_않는다() {
        // given
        final long id = fixture.observation(fixture.batch(DAY));
        fixture.forecast(id, false, 0);
        Objects objects = new Objects();
        service(objects, false).advance();
        jdbc.sql("DELETE FROM operational_archive_boundary").update();
        readyAgain();

        // when
        service(objects, true).advance();

        // then
        assertSoftly(softly -> {
            softly.assertThat(jobValue("failure_code")).isEqualTo("ARCHIVE_BOUNDARY_OPEN");
            softly.assertThat(jdbc.sql("SELECT count(*) FROM operational_archive_file WHERE purged")
                .query(Long.class).single()).isZero();
            softly.assertThat(fixture.count("forecast_evaluation_result")).isOne();
        });
    }

    @Test
    void 삭제_사이_쉬는_시간을_파일마다_둔다() {
        // given
        final long id = fixture.observation(fixture.batch(DAY));
        fixture.forecast(id, false, 0);
        Objects objects = new Objects();
        service(objects, false).advance();
        final long files = fixture.count("operational_archive_file");
        readyAgain();

        // when
        final long started = System.nanoTime();
        service(objects, true, new SimpleMeterRegistry(), Duration.ofMillis(100), Duration.ZERO).advance();
        final long elapsed = System.nanoTime() - started;

        // then
        assertThat(jobValue("state")).isEqualTo("DONE");
        assertThat(Duration.ofNanos(elapsed)).isGreaterThanOrEqualTo(Duration.ofMillis(100 * (files - 1)));
    }

    private ArchiveService service(Objects objects, final boolean deleteEnabled) {
        return service(objects, deleteEnabled, new SimpleMeterRegistry(), Duration.ZERO, Duration.ofMillis(100));
    }

    private ArchiveService service(
        Objects objects,
        final boolean deleteEnabled,
        SimpleMeterRegistry metrics,
        Duration purgePause,
        Duration lockRetryPause
    ) {
        return new ArchiveService(repository, objects, fixture.clock, metrics, "salmonbus/test/lake-v2/", 2,
            deleteEnabled, purgePause, lockRetryPause);
    }

    private ArchiveRepository.Job claim() {
        return repository.claim(ArchiveRetention.cutoff(fixture.clock)).orElseThrow();
    }

    private void readyAgain() {
        jdbc.sql("UPDATE operational_archive_job SET retry_at='-infinity',lease_until='-infinity'").update();
    }

    private String jobValue(String column) {
        return jdbc.sql("SELECT " + column + " FROM operational_archive_job").query(String.class).single();
    }

    private Instant boundary() {
        return jdbc.sql("SELECT closed_before FROM operational_archive_boundary WHERE route_version_id=?")
            .param(fixture.version).query(OffsetDateTime.class).optional().map(OffsetDateTime::toInstant).orElse(null);
    }

    private long model() {
        return jdbc.sql("""
            INSERT INTO model_deployment(deployment_key,release_id,model_key,model_version,bundle_digest,
                prediction_target_version,calculation_version,supported_scope_digest,data_until,state)
            VALUES(gen_random_uuid(),'test','seat','1',?,'target','calc',?,'2026-01-01','STAGED') RETURNING id
            """).params("1".repeat(64), "2".repeat(64)).query(Long.class).single();
    }

    private static void settlementWork(JdbcClient jdbc, final long observation) {
        jdbc.sql("""
            INSERT INTO alighting_forecast_evaluation_work(vehicle_observation_id,target_stop_order,target_kind,
                label_version,route_version_id)
            SELECT ?,2,'AFTER_ALIGHTING_ESTIMATE','label-v1',route_version_id FROM vehicle_observation WHERE id=?
            """).params(observation, observation).update();
    }

    private boolean routeLockAvailable() {
        try (Connection connection = connect(fixture)) {
            connection.setAutoCommit(false);
            try (PreparedStatement lock = connection.prepareStatement("""
                SELECT r.id FROM route r JOIN route_version v ON v.route_id=r.id WHERE v.id=? FOR UPDATE OF r NOWAIT
                """)) {
                lock.setLong(1, fixture.version);
                lock.executeQuery().close();
                return true;
            } catch (SQLException locked) {
                return false;
            } finally {
                connection.rollback();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static double retries(SimpleMeterRegistry metrics, String stage) {
        Counter counter = metrics.find("salmonbus_archive_lock_retries").tag("stage", stage).counter();
        return counter == null ? 0 : counter.count();
    }

    private static String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return "NONE";
    }

    private static Connection connect(OperationalArchiveIntegrationTest fixture) throws SQLException {
        return DriverManager.getConnection(fixture.database.jdbcUrl(), fixture.database.username(),
            fixture.database.password());
    }

    /** 다른 세션(worker 의 예보 커밋이나 하차 정산 저장)이 노선 행 잠금을 쥔 상태를 흉내 낸다. */
    static final class RouteLock implements AutoCloseable {
        private final Connection connection;
        private boolean finished;

        private RouteLock(Connection connection) {
            this.connection = connection;
        }

        static RouteLock hold(OperationalArchiveIntegrationTest fixture, final long version) {
            try {
                Connection connection = connect(fixture);
                connection.setAutoCommit(false);
                RouteLock lock = new RouteLock(connection);
                lock.execute("SELECT r.id FROM route r JOIN route_version v ON v.route_id=r.id WHERE v.id=?"
                    + " FOR UPDATE OF r", version);
                return lock;
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        }

        void execute(String sql, Object... values) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int index = 0; index < values.length; index++) {
                    statement.setObject(index + 1, values[index]);
                }
                statement.execute();
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        }

        CompletableFuture<Void> commitAfter(Duration delay) {
            return CompletableFuture.runAsync(this::commit,
                CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS));
        }

        private synchronized void commit() {
            if (finished) {
                return;
            }
            try {
                connection.commit();
                finished = true;
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        }

        @Override
        public synchronized void close() {
            try {
                if (!finished) {
                    connection.rollback();
                    finished = true;
                }
                connection.close();
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        }
    }

    /** 내보내기와 삭제 단계의 파일 읽기에 끼어들 수 있는 메모리 저장소다. */
    static final class Objects extends OperationalArchiveIntegrationTest.MemoryObjects {
        Runnable onExport;
        Runnable onPurgeRead;
        boolean routeFreeDuringExport;

        @Override
        public void putVerified(String key, byte[] bytes) {
            if (onExport != null && !key.contains("/manifest-")) {
                Runnable hook = onExport;
                onExport = null;
                hook.run();
            }
            super.putVerified(key, bytes);
        }

        @Override
        public byte[] read(String key, final int limit) {
            byte[] bytes = super.read(key, limit);
            if (onPurgeRead != null && published && !key.contains("/manifest-")) {
                Runnable hook = onPurgeRead;
                onPurgeRead = null;
                hook.run();
            }
            return bytes;
        }
    }
}
