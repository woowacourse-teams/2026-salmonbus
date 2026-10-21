package com.gustler.backend.arrivals.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.gustler.backend.arrival.contract.ArrivalSnapshot;
import com.gustler.backend.arrival.contract.ArrivalSnapshot.Prediction;
import com.gustler.backend.arrival.contract.ArrivalSnapshot.RouteArrival;
import com.gustler.backend.arrivals.domain.ArrivalStore;
import com.gustler.backend.arrivals.infrastructure.jdbc.ArrivalPersistenceTest.Config;
import com.gustler.backend.support.PostgresTestContainer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = Config.class)
class ArrivalPersistenceTest {
    private static final String STATION = "206000637";
    private static final Instant BASE = Instant.parse("2026-10-14T03:00:00Z");
    @Autowired
    ArrivalStore store;
    @Autowired
    JdbcClient jdbc;

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(PostgresTestContainer.class)
    static class Config {
        @Bean
        ArrivalStore arrivalStore(JdbcClient jdbc) {
            return new JdbcArrivalStore(jdbc, new ObjectMapper());
        }
    }

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM arrival_snapshot").update();
    }

    private ArrivalSnapshot snapshot(UUID id, Instant received) {
        return new ArrivalSnapshot(id, STATION, received, received.plusSeconds(40), received.plusSeconds(60),
            "2026-10-14 12:00:00", List.of(new RouteArrival("204000057", "3330", 22, 12,
                null, null, "RUN", List.of(new Prediction("vehicle-a", 1, 180, 3, 3,
                    0, null, null, 0, false)))));
    }

    @Test
    void 수신결과는_불변이며_같은_작업의_저장재시도는_중복되지_않는다() {
        // when
        ArrivalSnapshot snapshot = snapshot(UUID.randomUUID(), BASE);
        store.save(snapshot);
        store.save(snapshot);

        // then
        assertThat(jdbc.sql("SELECT count(*) FROM arrival_snapshot").query(Long.class).single()).isEqualTo(1);
        assertThat(store.latest(STATION)).contains(snapshot);

        // when
        Throwable phaseFailure1 = catchThrowable(() -> store.save(snapshot(snapshot.id(), BASE.plusSeconds(1))));

        // then
        assertThat(phaseFailure1)
            .isNotNull()
            .isInstanceOf(IllegalStateException.class);
        assertThat(store.latest(STATION)).contains(snapshot);
    }

    @Test
    void 새_수신은_INSERT하고_정류장별_최신_수신을_찾는다() {
        // when
        ArrivalSnapshot first = snapshot(UUID.randomUUID(), BASE);
        ArrivalSnapshot second = snapshot(UUID.randomUUID(), BASE.plusSeconds(40));
        store.save(first);
        store.save(second);

        // then
        assertThat(jdbc.sql("SELECT count(*) FROM arrival_snapshot").query(Long.class).single()).isEqualTo(2);
        assertThat(store.latest(STATION)).contains(second);
        assertThat(store.latest("206000532")).isEmpty();
    }

    @Test
    void 만료자료_정리는_제한된_건수만_삭제한다() {
        // when
        store.save(snapshot(UUID.randomUUID(), BASE.minusSeconds(1000)));
        store.save(snapshot(UUID.randomUUID(), BASE.minusSeconds(900)));
        ArrivalSnapshot fresh = snapshot(UUID.randomUUID(), BASE);
        store.save(fresh);

        // then
        assertThat(store.purgeExpiredBefore(BASE, 1)).isEqualTo(1);
        assertThat(store.purgeExpiredBefore(BASE, 500)).isEqualTo(1);
        assertThat(store.latest(STATION)).contains(fresh);
    }
}
