package com.gustler.backend.quota.infrastructure.jdbc;

import com.gustler.backend.quota.api.CallQuota;
import com.gustler.backend.quota.domain.CallQuotaStore;
import com.gustler.backend.quota.domain.DailyCallQuota;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 호출 예약을 갱신 한 문장으로 처리한다.
 *
 * <p>그날 행이 없으면 만들고, 있으면 잔여 한도가 있을 때만 사용량을 하나 늘린다.
 * 둘이 동시에 예약하면 나중 예약은 먼저 실행된 예약의 커밋을 기다린 뒤 갱신된 행으로 판정한다.
 * 마지막 한 번의 호출 한도에 둘이 경합해도 하나만 예약된다.
 *
 * <p>JPA 를 사용하지 않는다. 영속성 컨텍스트가 flush 시점을 결정하면 예약이 다른 트랜잭션에
 * 보이는 시점이 불분명해진다. 한도 판정은 이 시점에 달려 있다.
 */
@Repository
public class JdbcCallQuotaStore implements CallQuotaStore {
    /**
     * 그날 행이 이미 있으면 daily_limit 은 변경하지 않는다.
     * 설정을 바꿔도 그날 장부는 처음 정한 한도로 끝까지 센다.
     */
    private static final String RESERVE = """
        INSERT INTO daily_call_quota (provider, api_service, kst_date, key_alias, reserved_calls, daily_limit)
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT ON CONSTRAINT pk_daily_call_quota DO UPDATE
            SET reserved_calls = daily_call_quota.reserved_calls + ?
            WHERE daily_call_quota.reserved_calls + ? <= daily_call_quota.daily_limit
        """;

    private static final String EXCLUDE = """
        UPDATE daily_call_quota
            SET reserved_calls = daily_limit
            WHERE provider = ? AND api_service = ? AND kst_date = ? AND key_alias = ?
        RETURNING old.reserved_calls
        """;

    private static final int ONE_ROW_CHANGED = 1;

    private final JdbcClient jdbcClient;

    public JdbcCallQuotaStore(
        JdbcClient jdbcClient
    ) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public boolean reserve(
        DailyCallQuota.Reservation reservation
    ) {
        DailyCallQuota quota = reservation.quota();
        final int calls = reservation.calls();
        return jdbcClient.sql(RESERVE)
            .params(quota.service().provider(), quota.service().apiService(), quota.koreanDate(), quota.keyAlias(),
                calls, quota.dailyLimit(), calls, calls)
            .update() == ONE_ROW_CHANGED;
    }

    @Override
    public Optional<Integer> exclude(
        CallQuota service,
        LocalDate koreanDate,
        String keyAlias
    ) {
        return jdbcClient.sql(EXCLUDE)
            .params(service.provider(), service.apiService(), koreanDate, keyAlias)
            .query(Integer.class)
            .optional();
    }
}
