package com.gustler.backend.quota.infrastructure.jdbc;

import com.gustler.backend.quota.api.ArrivalQuotaPolicy;
import com.gustler.backend.quota.domain.ArrivalQuotaStore;
import com.gustler.backend.quota.domain.ArrivalQuotaStore.KeyLimit;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 합계 행부터 잠가 전체와 키별 예산을 한 트랜잭션에서 예약한다. */
public final class JdbcArrivalQuotaStore implements ArrivalQuotaStore {
    private static final String RESERVE = """
        INSERT INTO daily_call_quota(provider, api_service, kst_date, key_alias, reserved_calls, daily_limit)
        VALUES ('GBIS', 'BUS_ARRIVAL', ?, ?, 1, ?)
        ON CONFLICT ON CONSTRAINT pk_daily_call_quota DO UPDATE
        SET reserved_calls = daily_call_quota.reserved_calls + 1
        WHERE daily_call_quota.reserved_calls + 1 <= LEAST(daily_call_quota.daily_limit, ?)
        """;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public JdbcArrivalQuotaStore(JdbcClient jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
    }

    @Override
    public Optional<String> reserve(LocalDate date, final int totalLimit, List<KeyLimit> orderedKeys) {
        return transactions.execute(status -> {
            if (!reserve(date, ArrivalQuotaPolicy.TOTAL, totalLimit)) {
                return Optional.empty();
            }
            for (KeyLimit key : orderedKeys) {
                if (reserve(date, key.alias(), key.limit())) {
                    return Optional.of(key.alias());
                }
            }
            status.setRollbackOnly();
            return Optional.empty();
        });
    }

    private boolean reserve(LocalDate date, String alias, final int limit) {
        return jdbc.sql(RESERVE).params(date, alias, limit, limit).update() == 1;
    }

    @Override
    public void exclude(LocalDate date, String keyAlias) {
        transactions.executeWithoutResult(status -> jdbc.sql("""
            UPDATE daily_call_quota SET reserved_calls = daily_limit
            WHERE provider = 'GBIS' AND api_service = 'BUS_ARRIVAL'
              AND kst_date = ? AND key_alias = ?
            """).params(date, keyAlias).update());
    }
}
