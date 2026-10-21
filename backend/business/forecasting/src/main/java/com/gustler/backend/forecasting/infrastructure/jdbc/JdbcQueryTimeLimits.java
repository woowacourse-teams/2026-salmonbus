package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.application.evaluation.QueryTimeLimits;
import java.time.Duration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 업무 스레드가 DB 조회를 무제한 기다리지 않도록 설정된 쿼리 시간 예산을 적용한다.
 */
@Repository
public class JdbcQueryTimeLimits implements QueryTimeLimits {
    private final JdbcClient jdbc;

    public JdbcQueryTimeLimits(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void apply(Duration statementTimeout, Duration lockTimeout) {
        jdbc.sql("SELECT set_config('statement_timeout', :statement, true), set_config('lock_timeout', :lock, true)")
            .param("statement", statementTimeout.toMillis() + "ms")
            .param("lock", lockTimeout.toMillis() + "ms").query().singleRow();
    }
}
