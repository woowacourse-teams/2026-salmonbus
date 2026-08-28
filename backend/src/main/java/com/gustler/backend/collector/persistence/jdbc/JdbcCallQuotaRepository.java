package com.gustler.backend.collector.persistence.jdbc;

import com.gustler.backend.collector.CallQuota;
import com.gustler.backend.collector.CallQuotaRepository;
import java.time.LocalDate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 자리 잡기를 갱신 한 문장으로 끝낸다.
 *
 * <p>그날 행이 없으면 만들고, 있으면 자리가 남았을 때만 하나 올린다.
 * 둘이 동시에 들어오면 뒤엣것이 앞엣것의 커밋을 기다렸다가 갱신된 행을 다시 보고 판정한다.
 * 그래서 마지막 한 자리에 둘이 붙어도 하나만 잡는다.
 *
 * <p>JPA 를 안 쓴다. 영속성 컨텍스트가 flush 시점을 잡고 있으면 자리를 잡은 사실이 다른 트랜잭션에
 * 언제 보이는지가 흐려진다. 한도는 그 시점이 전부다.
 */
@Repository
public class JdbcCallQuotaRepository implements CallQuotaRepository {

    /**
     * 그날 행이 이미 있으면 daily_limit 은 안 건드린다.
     * 설정을 바꿔도 그날 장부는 처음 정한 한도로 끝까지 센다.
     */
    private static final String RESERVE_ONE = """
        INSERT INTO daily_call_quota (provider, api_service, kst_date, reserved_calls, daily_limit)
        VALUES (?, ?, ?, 1, ?)
        ON CONFLICT ON CONSTRAINT pk_daily_call_quota DO UPDATE
            SET reserved_calls = daily_call_quota.reserved_calls + 1
            WHERE daily_call_quota.reserved_calls < daily_call_quota.daily_limit
        """;

    private static final int RESERVED_ONE_SEAT = 1;

    private final JdbcClient jdbcClient;

    public JdbcCallQuotaRepository(
        JdbcClient jdbcClient
    ) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public boolean reserveOne(
        CallQuota quota,
        LocalDate kstDate,
        final int dailyLimit
    ) {
        return jdbcClient.sql(RESERVE_ONE)
            .params(quota.provider(), quota.apiService(), kstDate, dailyLimit)
            .update() == RESERVED_ONE_SEAT;
    }
}
