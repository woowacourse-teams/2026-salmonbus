package com.gustler.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.support.IntegrationTest;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class DailyCallQuotaTableTest {

    private static final String PROVIDER_GBIS = "GBIS";
    private static final String API_SERVICE_BUS_LOCATION = "BUS_LOCATION";
    private static final LocalDate KST_DATE = LocalDate.of(2026, 8, 28);
    private static final int DAILY_LIMIT_10000 = 10_000;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void 쓴_횟수가_한도와_같은_장부는_저장된다() {
        // when
        insertQuota(DAILY_LIMIT_10000, DAILY_LIMIT_10000);

        // then
        assertThat(storedQuotaCount()).isEqualTo(1);
    }

    @Test
    void 쓴_횟수가_한도를_넘는_장부는_저장되지_않는다() {
        // when & then
        assertThatThrownBy(() -> insertQuota(DAILY_LIMIT_10000 + 1, DAILY_LIMIT_10000))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 쓴_횟수가_음수인_장부는_저장되지_않는다() {
        // when & then
        assertThatThrownBy(() -> insertQuota(-1, DAILY_LIMIT_10000))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 한도가_0인_장부는_저장되지_않는다() {
        // when & then
        assertThatThrownBy(() -> insertQuota(0, 0))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertQuota(
        final int reservedCalls,
        final int dailyLimit
    ) {
        jdbcClient.sql("""
                INSERT INTO daily_call_quota (provider, api_service, kst_date, reserved_calls, daily_limit)
                VALUES (?, ?, ?, ?, ?)
                """)
            .params(PROVIDER_GBIS, API_SERVICE_BUS_LOCATION, KST_DATE, reservedCalls, dailyLimit)
            .update();
    }

    private int storedQuotaCount() {
        return jdbcClient.sql("SELECT count(*) FROM daily_call_quota WHERE kst_date = ?")
            .param(KST_DATE)
            .query(Integer.class)
            .single();
    }
}
