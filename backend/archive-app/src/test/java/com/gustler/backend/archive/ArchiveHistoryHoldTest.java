package com.gustler.backend.archive;

import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;

/**
 * 최근 관측의 줄 단위 이관을 미루는 기준({@code archive.history-hold})을 실제 PG에서 본다. 기본값({@code checkpoint})은 옛 예보 경로의
 * 체크포인트 보호선을 보고, {@code window}는 그 표를 보지 않고 창보다 최근인 날짜만 미룬다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ArchiveHistoryHoldTest {
    private static final String FRIDAY = "2026-11-06T10:00Z";

    OperationalArchiveIntegrationTest fixture;
    JdbcTransactionManager manager;

    @BeforeAll
    void migrate() {
        fixture = new OperationalArchiveIntegrationTest();
        fixture.migrate();
        manager = new JdbcTransactionManager(new DriverManagerDataSource(fixture.database.jdbcUrl(),
            fixture.database.username(), fixture.database.password()));
    }

    @AfterAll
    void close() {
        fixture.close();
    }

    @BeforeEach
    void setup() {
        fixture.setup();
    }

    @Test
    void 기본값은_체크포인트_보호선이_날짜를_덮으면_이관하지_않는다() {
        // given
        protectedByCheckpoint();

        // when
        service(new ArchiveRepository(fixture.jdbc, manager)).advance();

        // then
        assertSoftly(softly -> {
            softly.assertThat(failureCode()).isEqualTo("ALIGHTING_HISTORY_REQUIRED");
            softly.assertThat(fixture.count("observation_batch")).isOne();
        });
    }

    @Test
    void 창_기준은_체크포인트_표를_보지_않고_창보다_오래된_날짜를_이관한다() {
        // given
        protectedByCheckpoint();
        ArchiveRepository repository = repository("window", Duration.ofHours(12));

        // when
        service(repository).advance();

        // then
        assertSoftly(softly -> {
            softly.assertThat(fixture.count("observation_batch")).isZero();
            softly.assertThat(fixture.count("vehicle_observation")).isZero();
        });
    }

    @Test
    void 창_기준은_체크포인트_없이도_창보다_최근인_날짜를_이관하지_않는다() {
        // given
        fixture.observation(fixture.batch(FRIDAY));
        ArchiveRepository repository = repository("WINDOW", Duration.ofDays(3));

        // when
        service(repository).advance();

        // then
        assertSoftly(softly -> {
            softly.assertThat(fixture.count("alighting_history_checkpoint")).isZero();
            softly.assertThat(failureCode()).isEqualTo("ALIGHTING_HISTORY_REQUIRED");
            softly.assertThat(fixture.count("observation_batch")).isOne();
        });
    }

    @Test
    void 모르는_기준이나_1시간보다_짧은_창은_받지_않는다() {
        // given
        ArchiveRepository repository = new ArchiveRepository(fixture.jdbc, manager);

        // when
        Throwable unknownHold = catchThrowable(() -> repository.setHistoryHold("latest"));
        Throwable shortWindow = catchThrowable(() -> repository.setHistoryHoldWindow(Duration.ofMinutes(59)));

        // then
        assertSoftly(softly -> {
            softly.assertThat(unknownHold).isInstanceOf(IllegalArgumentException.class);
            softly.assertThat(shortWindow).isInstanceOf(IllegalArgumentException.class);
        });
    }

    private void protectedByCheckpoint() {
        final long friday = fixture.batch(FRIDAY);
        fixture.observation(friday);
        fixture.jdbc.sql("""
            INSERT INTO alighting_history_checkpoint VALUES(?,'2026-11-06T10:00:01Z',?,'2026-11-06T09:58:31Z')
            """).params(fixture.version, friday).update();
    }

    private ArchiveRepository repository(String hold, Duration window) {
        ArchiveRepository repository = new ArchiveRepository(fixture.jdbc, manager);
        repository.setHistoryHold(hold);
        repository.setHistoryHoldWindow(window);
        repository.setClock(fixture.clock);
        return repository;
    }

    private ArchiveService service(ArchiveRepository repository) {
        return new ArchiveService(repository, new OperationalArchiveIntegrationTest.MemoryObjects(), fixture.clock,
            new SimpleMeterRegistry(), "salmonbus/test/lake-v2/", 2, true);
    }

    private String failureCode() {
        return fixture.jdbc.sql("SELECT failure_code FROM operational_archive_job").query(String.class).single();
    }
}
