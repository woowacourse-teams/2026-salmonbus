package com.gustler.backend.arrivals;

import static org.assertj.core.api.Assertions.*;

import com.gustler.backend.arrival.ArrivalSnapshot;
import com.gustler.backend.arrivals.application.ArrivalStore;
import com.gustler.backend.arrivals.infrastructure.JdbcArrivalStore;
import com.gustler.backend.quota.api.*;
import com.gustler.backend.quota.infrastructure.jdbc.JdbcArrivalCallQuota;
import com.gustler.backend.support.PostgresTestContainer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = ArrivalPersistenceTest.Config.class)
class ArrivalPersistenceTest {
    private static final String STATION = "206000637";
    private static final Instant BASE = Instant.parse("2026-10-14T03:00:00Z");
    @Autowired ArrivalStore store;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager manager;

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(PostgresTestContainer.class)
    static class Config {
        @Bean ArrivalStore arrivalStore(JdbcClient jdbc) { return new JdbcArrivalStore(jdbc, new ObjectMapper()); }
    }

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM arrival_snapshot").update();
        jdbc.sql("DELETE FROM daily_call_quota WHERE api_service = 'BUS_ARRIVAL'").update();
    }

    private ArrivalSnapshot snapshot(UUID id, Instant received) {
        return new ArrivalSnapshot(id, STATION, received, received.plusSeconds(40), received.plusSeconds(60),
            "2026-10-14 12:00:00", List.of(new ArrivalSnapshot.RouteArrival("204000057", "3330", 22, 12,
            null, null, "RUN", List.of(new ArrivalSnapshot.Prediction("vehicle-a", 1, 180, 3, 3,
            0, null, null, 0, false)))));
    }

    @Test
    void 수신결과는_불변이며_같은_작업의_저장재시도는_중복되지_않는다() {
        var snapshot = snapshot(UUID.randomUUID(), BASE);
        store.save(snapshot); store.save(snapshot);
        assertThat(jdbc.sql("SELECT count(*) FROM arrival_snapshot").query(Long.class).single()).isEqualTo(1);
        assertThat(store.latest(STATION)).contains(snapshot);
        assertThatThrownBy(() -> store.save(snapshot(snapshot.id(), BASE.plusSeconds(1))))
            .isInstanceOf(IllegalStateException.class);
        assertThat(store.latest(STATION)).contains(snapshot);
    }

    @Test
    void 새_수신은_INSERT하고_정류장별_최신_수신을_찾는다() {
        var first = snapshot(UUID.randomUUID(), BASE);
        var second = snapshot(UUID.randomUUID(), BASE.plusSeconds(40));
        store.save(first); store.save(second);
        assertThat(jdbc.sql("SELECT count(*) FROM arrival_snapshot").query(Long.class).single()).isEqualTo(2);
        assertThat(store.latest(STATION)).contains(second);
        assertThat(store.latest("206000532")).isEmpty();
    }

    @Test
    void 만료자료_정리는_제한된_건수만_삭제한다() {
        store.save(snapshot(UUID.randomUUID(), BASE.minusSeconds(1000)));
        store.save(snapshot(UUID.randomUUID(), BASE.minusSeconds(900)));
        var fresh = snapshot(UUID.randomUUID(), BASE);
        store.save(fresh);
        assertThat(store.purgeExpiredBefore(BASE, 1)).isEqualTo(1);
        assertThat(store.purgeExpiredBefore(BASE, 500)).isEqualTo(1);
        assertThat(store.latest(STATION)).contains(fresh);
    }

    private ArrivalCallQuota quota(int total, Map<String, Integer> keys) {
        return new JdbcArrivalCallQuota(jdbc, manager, new ArrivalQuotaPolicy(total, keys));
    }

    private int reserved(String alias) {
        return jdbc.sql("""
            SELECT COALESCE(sum(reserved_calls), 0)::integer FROM daily_call_quota
            WHERE api_service = 'BUS_ARRIVAL' AND key_alias = ?
            """).param(alias).query(Integer.class).single();
    }

    @Test
    void 여러_키가_있어도_전체의_마지막_자리를_동시에_예약하지_않는다() throws Exception {
        ArrivalCallQuota quota = quota(7, Map.of("a", 10, "b", 10));
        List<Future<Boolean>> requests = new ArrayList<>();
        try (var callers = Executors.newFixedThreadPool(12)) {
            for (int i = 0; i < 100; i++) requests.add(callers.submit(() -> quota.reserve(BASE).isPresent()));
            int successes = 0;
            for (var request : requests) if (request.get(10, TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(7);
        }
        assertThat(reserved(ArrivalQuotaPolicy.TOTAL)).isEqualTo(7);
        assertThat(reserved("a") + reserved("b")).isEqualTo(7);
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
