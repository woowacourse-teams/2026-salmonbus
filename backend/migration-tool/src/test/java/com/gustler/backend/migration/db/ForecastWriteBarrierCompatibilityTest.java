package com.gustler.backend.migration.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.processor.ForecastWriteBarrierState;
import com.gustler.backend.processor.persistence.jdbc.JdbcForecastWriteBarrier;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ForecastWriteBarrierCompatibilityTest extends PostgresMigrationTestSupport {

    @Test
    void missingControlTableAllowsWritesButSqlFailureIsFailClosed() throws Exception {
        ForecastWriteBarrierState state = new ForecastWriteBarrierState(
            Clock.fixed(Instant.parse("2026-09-03T00:00:00Z"), ZoneOffset.UTC));
        JdbcForecastWriteBarrier barrier = new JdbcForecastWriteBarrier(
            JdbcClient.create(dataSource()), state);

        assertThat(enter(barrier)).isTrue();

        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            CREATE TABLE forecast_cutover_control (singleton boolean PRIMARY KEY)
            """)) {
            statement.execute();
        }
        assertThat(enter(barrier)).isFalse();
        assertThat(state.snapshot().lastSkipReason()).isEqualTo("DATABASE_CHECK_FAILED");
    }

    private static boolean enter(
        JdbcForecastWriteBarrier barrier
    ) {
        Boolean entered = new TransactionTemplate(new DataSourceTransactionManager(dataSource()))
            .execute(status -> barrier.enter());
        return Boolean.TRUE.equals(entered);
    }

    private static PGSimpleDataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        return dataSource;
    }
}
