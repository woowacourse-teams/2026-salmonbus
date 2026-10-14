package com.gustler.backend.quota.infrastructure.jdbc;

import com.gustler.backend.quota.api.ArrivalCallQuota;
import com.gustler.backend.quota.api.ArrivalQuotaPolicy;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 합계 행부터 잠가 전체 예산의 마지막 한 자리도 한 번만 예약한다. */
public final class JdbcArrivalCallQuota implements ArrivalCallQuota {
    private static final String RESERVE = """
        INSERT INTO daily_call_quota(provider, api_service, kst_date, key_alias, reserved_calls, daily_limit)
        VALUES ('GBIS', 'BUS_ARRIVAL', ?, ?, 1, ?)
        ON CONFLICT ON CONSTRAINT pk_daily_call_quota DO UPDATE
        SET reserved_calls = daily_call_quota.reserved_calls + 1
        WHERE daily_call_quota.reserved_calls + 1 <= LEAST(daily_call_quota.daily_limit, ?)
        """;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ArrivalQuotaPolicy policy;
    private final List<String> aliases;
    private final AtomicInteger cursor = new AtomicInteger();

    public JdbcArrivalCallQuota(JdbcClient jdbc, PlatformTransactionManager manager, ArrivalQuotaPolicy policy) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.policy = policy;
        this.aliases = policy.keyLimits().keySet().stream().sorted().toList();
    }

    @Override
    public Optional<Reservation> reserve(Instant dispatchAt) {
        LocalDate date = dispatchAt.atZone(KOREA).toLocalDate();
        return transactions.execute(status -> {
            if (!reserve(date, ArrivalQuotaPolicy.TOTAL, policy.dailyLimit())) return Optional.empty();
            int start = Math.floorMod(cursor.getAndIncrement(), aliases.size());
            for (int offset = 0; offset < aliases.size(); offset++) {
                String alias = aliases.get((start + offset) % aliases.size());
                if (reserve(date, alias, policy.keyLimits().get(alias))) {
                    return Optional.of(new Reservation(alias, date));
                }
            }
            // 모든 키가 소진됐으면 먼저 증가시킨 전체 예약도 되돌린다.
            status.setRollbackOnly();
            return Optional.empty();
        });
    }

    private boolean reserve(LocalDate date, String alias, int limit) {
        return jdbc.sql(RESERVE).params(date, alias, limit, limit).update() == 1;
    }

    @Override
    public void exclude(Reservation reservation) {
        transactions.executeWithoutResult(status -> jdbc.sql("""
            UPDATE daily_call_quota SET reserved_calls = daily_limit
            WHERE provider = 'GBIS' AND api_service = 'BUS_ARRIVAL'
              AND kst_date = ? AND key_alias = ?
            """).params(reservation.koreanDate(), reservation.keyAlias()).update());
    }
}
