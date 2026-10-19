package com.gustler.backend.quota.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.quota.api.ArrivalCallQuota;
import com.gustler.backend.quota.api.ArrivalQuotaPolicy;
import com.gustler.backend.quota.application.ArrivalCallQuotaLedger;
import com.gustler.backend.support.PostgresTestContainer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest(classes = JdbcArrivalQuotaStoreTest.Config.class)
class JdbcArrivalQuotaStoreTest {
    private static final Instant BASE = Instant.parse("2026-10-14T03:00:00Z");

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(PostgresTestContainer.class)
    static class Config { }

    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager manager;

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM daily_call_quota WHERE api_service = 'BUS_ARRIVAL'").update();
    }

    private ArrivalCallQuota quota(int total, Map<String, Integer> keys) {
        return new ArrivalCallQuotaLedger(new JdbcArrivalQuotaStore(jdbc, manager), new ArrivalQuotaPolicy(total, keys));
    }

    private int reserved(String alias) {
        return jdbc.sql("""
            SELECT COALESCE(sum(reserved_calls), 0)::integer FROM daily_call_quota
            WHERE api_service = 'BUS_ARRIVAL' AND key_alias = ?
            """).param(alias).query(Integer.class).single();
    }

    @Test
    void 여러_키가_있어도_전체의_마지막_자리를_동시에_예약하지_않는다() throws Exception {
        List<ArrivalCallQuota> ledgers = List.of(quota(7, Map.of("a", 10, "b", 10)),
            quota(7, Map.of("a", 10, "b", 10)));
        List<Future<Boolean>> requests = new ArrayList<>();
        try (var callers = Executors.newFixedThreadPool(12)) {
            for (int i = 0; i < 100; i++) {
                ArrivalCallQuota ledger = ledgers.get(i % ledgers.size());
                requests.add(callers.submit(() -> ledger.reserve(BASE).isPresent()));
            }
            int successes = 0;
            for (var request : requests) if (request.get(10, TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(7);
        }
        assertThat(reserved(ArrivalQuotaPolicy.TOTAL)).isEqualTo(7);
        assertThat(reserved("a") + reserved("b")).isEqualTo(7);
    }

    @Test
    void 키가_다섯_개면_다섯_별칭에_고르게_나누고_키별_한도에서_멈춘다() {
        ArrivalCallQuota quota = quota(100, Map.of("a", 2, "b", 2, "c", 2, "d", 2, "e", 2));
        for (int i = 0; i < 10; i++) assertThat(quota.reserve(BASE)).isPresent();
        assertThat(quota.reserve(BASE)).isEmpty();
        for (String alias : List.of("a", "b", "c", "d", "e")) assertThat(reserved(alias)).isEqualTo(2);
        assertThat(reserved(ArrivalQuotaPolicy.TOTAL)).isEqualTo(10);
    }

    @Test
    void 키가_모두_소진되면_전체_예약도_롤백한다() {
        ArrivalCallQuota quota = quota(10, Map.of("a", 1, "b", 1));
        assertThat(quota.reserve(BASE)).isPresent();
        assertThat(quota.reserve(BASE)).isPresent();
        assertThat(quota.reserve(BASE)).isEmpty();
        assertThat(reserved(ArrivalQuotaPolicy.TOTAL)).isEqualTo(2);
    }

    @Test
    void 거절된_키는_다른_키의_기회를_소진하지_않는다() {
        ArrivalCallQuota quota = quota(10, Map.of("a", 3, "b", 3));
        var first = quota.reserve(BASE).orElseThrow();
        quota.exclude(first);
        for (int i = 0; i < 3; i++) assertThat(quota.reserve(BASE).orElseThrow().keyAlias()).isEqualTo("b");
        assertThat(quota.reserve(BASE)).isEmpty();
        assertThat(reserved(ArrivalQuotaPolicy.TOTAL)).isEqualTo(4);
    }

    @Test
    void 한국_자정에_장부가_바뀌고_기존_예약은_유지한다() {
        ArrivalCallQuota quota = quota(1, Map.of("a", 1));
        Instant before = Instant.parse("2026-10-14T14:59:59Z");
        Instant after = before.plusSeconds(1);
        var old = quota.reserve(before).orElseThrow();
        assertThat(old.covers(after)).isFalse();
        assertThat(quota.reserve(after)).isPresent();
        assertThat(reserved(ArrivalQuotaPolicy.TOTAL)).isEqualTo(2);
    }

    @Test
    void 위치정보_장부의_소진은_도착정보_예약을_막지_않는다() {
        jdbc.sql("""
            INSERT INTO daily_call_quota(provider, api_service, kst_date, key_alias, reserved_calls, daily_limit)
            VALUES ('GBIS', 'BUS_LOCATION', '2026-10-14', 'a', 10000, 10000)
            ON CONFLICT DO NOTHING
            """).update();
        assertThat(quota(1, Map.of("a", 1)).reserve(BASE)).isPresent();
    }
}
