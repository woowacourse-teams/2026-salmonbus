package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.application.evaluation.QueryTimeLimits;
import java.time.Duration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class JdbcQueryTimeLimits implements QueryTimeLimits {

    private final JdbcClient jdbc;

    public JdbcQueryTimeLimits(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void apply(final Duration statementTimeout, final Duration lockTimeout) {
        jdbc.sql("SELECT set_config('statement_timeout', :statement, true), set_config('lock_timeout', :lock, true)")
            .param("statement", statementTimeout.toMillis() + "ms")
            .param("lock", lockTimeout.toMillis() + "ms").query().singleRow();
    }
}
