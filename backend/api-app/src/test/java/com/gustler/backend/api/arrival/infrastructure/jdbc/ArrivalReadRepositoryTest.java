package com.gustler.backend.api.arrival.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.arrival.application.ArrivalReadRepository;
import com.gustler.backend.api.arrival.domain.ArrivalVisit;
import com.gustler.backend.api.arrival.infrastructure.jdbc.ArrivalReadRepositoryTest.Config;
import com.gustler.backend.arrival.contract.ArrivalSnapshot;
import com.gustler.backend.support.PostgresTestContainer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
class ArrivalReadRepositoryTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(PostgresTestContainer.class)
    static class Config {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        ArrivalReadRepository repository(JdbcClient jdbc, ObjectMapper json) {
            return new JdbcArrivalReadRepository(jdbc, json);
        }
    }

    @Autowired
    JdbcClient jdbc;
    @Autowired
    ObjectMapper json;
    @Autowired
    ArrivalReadRepository repository;

    @Test
    void 같은_정류장의_왕복방문과_전체수신결과를_실제_DB에서_읽는다() {
        // when
        final long route = jdbc.sql("""
            INSERT INTO route(public_route_id, source_id, source_route_id, display_name, start_stop_name, end_stop_name)
            VALUES ('204000057', 'GBIS', '204000057', '3330', 'A', 'B') RETURNING id
            """).query(Long.class).single();
        final long version = jdbc.sql("""
            INSERT INTO route_version(route_id, turn_sequence, content_digest, valid_from)
            VALUES (?, 43, ?, '2026-10-13T00:00:00Z') RETURNING id
            """).params(route, "a".repeat(64)).query(Long.class).single();
        jdbc.sql("""
            INSERT INTO route_stop(route_version_id, stop_order, stop_id, name, direction, boarding_allowed)
            VALUES (?, 22, '206000637', 'A', 'UP', true), (?, 65, '206000637', 'A', 'DOWN', true)
            """).params(version, version).update();
        List<ArrivalVisit> visits = repository.visits("206000637");

        // then
        assertThat(visits).hasSize(2);
        assertThat(visits).extracting(ArrivalVisit::stopSequence).containsExactly(22, 65);
        assertThat(visits).allSatisfy(visit -> assertThat(visit.referenceVersionId()).isEqualTo(version));

        Instant received = Instant.parse("2026-10-14T03:00:00Z");
        ArrivalSnapshot snapshot =
            new ArrivalSnapshot(UUID.randomUUID(), "206000637", received, received.plusSeconds(40),
                received.plusSeconds(60), null, List.of());
        jdbc.sql("""
            INSERT INTO arrival_snapshot(id, provider, operation, station_id, received_at, refresh_after_at,
                stale_at, normalization_version, payload_digest, payload)
            VALUES (?, 'GBIS', 'ARRIVAL_LIST_V2', '206000637', ?, ?, ?, 'fixture', ?, CAST(? AS jsonb))
            """).params(snapshot.id(), Timestamp.from(received), Timestamp.from(received.plusSeconds(40)),
            Timestamp.from(received.plusSeconds(60)), "a".repeat(64), json.writeValueAsString(snapshot)).update();
        assertThat(repository.latest("206000637")).contains(snapshot);
        assertThat(repository.latest("206000532")).isEmpty();
    }
}
