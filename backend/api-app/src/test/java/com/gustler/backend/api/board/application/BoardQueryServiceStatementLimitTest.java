package com.gustler.backend.api.board.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.gustler.backend.api.error.ServiceUnavailableException;
import com.gustler.backend.api.route.RouteNotFoundException;
import com.gustler.backend.support.IntegrationTest;
import com.gustler.backend.support.TestDatabase;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.assertj.core.api.AutoCloseableSoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 스냅샷 문장은 행을 찾기 전에 테이블 잠금부터 기다린다.
 */
@IntegrationTest
class BoardQueryServiceStatementLimitTest {
    private static final String UNKNOWN_ROUTE = "999999998";
    private static final Duration LIMIT = Duration.ofMillis(300);

    private final ExecutorService reader = Executors.newSingleThreadExecutor();

    @Autowired
    private BoardQueryService boards;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TestDatabase database;

    @AfterEach
    void stopReader() {
        reader.shutdownNow();
    }

    @Test
    void 잠긴_테이블을_읽는_문장은_제한_시간에_끝나고_연결을_돌려주며_제한은_풀에_남지_않는다() throws Exception {
        // given
        Throwable failure;
        Duration elapsed;
        int activeRightAfter;
        try (Connection locker = database.connection()) {
            locker.setAutoCommit(false);
            try (Statement statement = locker.createStatement()) {
                statement.execute("LOCK TABLE forecast_run IN ACCESS EXCLUSIVE MODE");
            }

            // when
            long startedAt = System.nanoTime();
            Future<Throwable> reading = reader.submit(() -> catchThrowable(() ->
                boards.readBoardsWithStatementLimit(List.of(UNKNOWN_ROUTE), LIMIT)));
            failure = reading.get(10, TimeUnit.SECONDS);
            elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
            activeRightAfter = activeConnections();
            locker.rollback();
        }

        // then
        List<String> limits = limitsOnEveryPoolConnection();
        BoardBatchRead next = boards.readBoardsWithStatementLimit(List.of(UNKNOWN_ROUTE), LIMIT)
            .get(UNKNOWN_ROUTE);
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(failure).isInstanceOf(ServiceUnavailableException.class);
            softly.assertThat(elapsed).isGreaterThanOrEqualTo(LIMIT);
            softly.assertThat(activeRightAfter).isZero();
            softly.assertThat(limits).hasSize(pool().getMaximumPoolSize()).containsOnly("0 0");
            softly.assertThat(next.failure()).isInstanceOf(RouteNotFoundException.class);
        }
    }

    private List<String> limitsOnEveryPoolConnection() throws SQLException {
        List<Connection> borrowed = new ArrayList<>();
        List<String> limits = new ArrayList<>();
        try {
            for (int index = 0; index < pool().getMaximumPoolSize(); index++) {
                Connection connection = dataSource.getConnection();
                borrowed.add(connection);
                limits.add(show(connection, "statement_timeout") + " " + show(connection, "lock_timeout"));
            }
        } finally {
            for (Connection connection : borrowed) {
                connection.close();
            }
        }
        return limits;
    }

    private static String show(Connection connection, String setting) throws SQLException {
        try (Statement statement = connection.createStatement();
            ResultSet row = statement.executeQuery("SHOW " + setting)) {
            row.next();
            return row.getString(1);
        }
    }

    private int activeConnections() throws SQLException {
        return pool().getHikariPoolMXBean().getActiveConnections();
    }

    private HikariDataSource pool() throws SQLException {
        return dataSource.unwrap(HikariDataSource.class);
    }
}
