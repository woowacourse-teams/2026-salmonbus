package com.gustler.backend.processor.persistence.jdbc;

import com.gustler.backend.processor.ForecastWriteBarrier;
import com.gustler.backend.processor.ForecastWriteBarrierState;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
public class JdbcForecastWriteBarrier implements ForecastWriteBarrier {

    private static final Logger log = LoggerFactory.getLogger(JdbcForecastWriteBarrier.class);

    public static final int LOCK_NAMESPACE = 1_920_224_641;
    public static final int LOCK_KEY = 4_041;

    private static final String CONTROL_TABLE = "public.forecast_cutover_control";
    private static final long WARN_AFTER_CONSECUTIVE_SKIPS = 6;

    private final JdbcClient jdbcClient;
    private final ForecastWriteBarrierState state;

    @Autowired
    public JdbcForecastWriteBarrier(
        JdbcClient jdbcClient,
        ForecastWriteBarrierState state
    ) {
        this.jdbcClient = jdbcClient;
        this.state = state;
    }

    public JdbcForecastWriteBarrier(
        JdbcClient jdbcClient
    ) {
        this(jdbcClient, new ForecastWriteBarrierState(Clock.systemUTC()));
    }

    @Override
    public boolean enter() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("forecast write fence는 transaction 안에서만 잡는다");
        }
        try {
            if (!controlTableExists()) {
                state.recordEntered();
                return true;
            }
            boolean entered = jdbcClient.sql("SELECT pg_try_advisory_xact_lock_shared(?, ?)")
                .params(LOCK_NAMESPACE, LOCK_KEY)
                .query(Boolean.class)
                .single();
            if (!entered) {
                return skip("EXCLUSIVE_LOCK_BUSY");
            }
            boolean allowed = !jdbcClient.sql(
                "SELECT writes_paused FROM forecast_cutover_control WHERE singleton = true")
                .query(Boolean.class)
                .single();
            if (!allowed) {
                return skip("WRITES_PAUSED");
            }
            state.recordEntered();
            return true;
        } catch (DataAccessException error) {
            state.recordSkipped("DATABASE_CHECK_FAILED");
            log.error("forecast write fence DB 확인에 실패해 이번 파생 write cycle을 건너뛴다");
            return false;
        }
    }

    private boolean skip(
        String reason
    ) {
        long consecutive = state.recordSkipped(reason);
        if (consecutive >= WARN_AFTER_CONSECUTIVE_SKIPS
            && consecutive % WARN_AFTER_CONSECUTIVE_SKIPS == 0) {
            log.warn("forecast cutover fence로 파생 write cycle을 연속 {}회 건너뛰었다. 사유={}",
                consecutive, reason);
        } else {
            log.info("forecast cutover fence로 이번 파생 write cycle을 건너뛴다. 사유={}", reason);
        }
        return false;
    }

    private boolean controlTableExists() {
        return jdbcClient.sql("SELECT to_regclass(?) IS NOT NULL")
            .param(CONTROL_TABLE)
            .query(Boolean.class)
            .single();
    }
}
