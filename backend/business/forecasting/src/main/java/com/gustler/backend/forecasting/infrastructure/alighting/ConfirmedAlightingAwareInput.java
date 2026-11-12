package com.gustler.backend.forecasting.infrastructure.alighting;

import com.gustler.backend.forecasting.application.alighting.AlightingAwareFeatureUnavailableException;
import com.gustler.backend.forecasting.application.alighting.AlightingAwareInputConfirmation;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 새 모델은 계산 전에 입력 시각을 고정한다. 재발행 시 기존 시각을 덮어쓰지 않는다. */
@Component
public class ConfirmedAlightingAwareInput implements AlightingAwareInputConfirmation {
    static final String MAX_CONFIRMATION_DELAY = "interval '90 seconds'";

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public ConfirmedAlightingAwareInput(JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setTimeout(5);
    }

    /** 새 트랜잭션에서 확정하고, 확정 판본의 조회 조건을 충족하는 배치만 계산 대상으로 반환한다. */
    @Override
    public boolean confirm(final long batchId, Instant at) {
        return Boolean.TRUE.equals(transaction.execute(status -> jdbc.sql("""
            UPDATE observation_batch SET input_confirmed_at=coalesce(input_confirmed_at,:at)
            WHERE id=:id AND ingestion_origin='LIVE' AND outcome IN ('SUCCESS_ROWS','SUCCESS_EMPTY')
              AND response_received_at IS NOT NULL AND stored_rows IS NOT NULL
            RETURNING stored_rows>0 AND input_confirmed_at BETWEEN response_received_at
              AND response_received_at+%s
            """.formatted(MAX_CONFIRMATION_DELAY)).param("id", batchId).param("at", at.atOffset(ZoneOffset.UTC)).query(Boolean.class).optional()
            .orElseThrow(() -> new AlightingAwareFeatureUnavailableException("ALIGHTING_AWARE_INPUT_NOT_READY"))));
    }
}
