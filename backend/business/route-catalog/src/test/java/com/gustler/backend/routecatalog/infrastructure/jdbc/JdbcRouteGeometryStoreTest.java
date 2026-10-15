package com.gustler.backend.routecatalog.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.routecatalog.domain.geometry.RouteGeometrySource;
import com.gustler.backend.routecatalog.domain.geometry.RouteGeometryStore;
import com.gustler.backend.routecatalog.domain.geometry.RouteShape;
import com.gustler.backend.support.PostgresTestContainer;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.Executors;
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

@SpringBootTest(classes = JdbcRouteGeometryStoreTest.Config.class)
class JdbcRouteGeometryStoreTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({PostgresTestContainer.class, JdbcRouteGeometryStore.class})
    static class Config { @Bean ObjectMapper objectMapper() { return new ObjectMapper(); } }
    @Autowired RouteGeometryStore store;
    @Autowired JdbcClient jdbc;
    private static final OffsetDateTime BASE = OffsetDateTime.parse("2026-10-14T12:00:00Z");

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE route CASCADE").update();
        long route = jdbc.sql("""
            INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)
            VALUES ('204000057','GBIS','204000057','3330','A','B') RETURNING id
            """).query(Long.class).single();
        jdbc.sql("INSERT INTO route_version(route_id,content_digest,valid_from) VALUES (?,?,'2026-10-13T00:00:00Z')")
            .params(route, "a".repeat(64)).update();
    }

    private RouteGeometrySource.Read read(int second, double x) {
        return new RouteGeometrySource.Read(new RouteShape(List.of(new RouteShape.Point(127, 37),
            new RouteShape.Point(x, 37.1))), BASE.plusSeconds(second));
    }

    private int count() { return jdbc.sql("SELECT count(*) FROM route_geometry").query(Integer.class).single(); }

    @Test
    void 같은_형상은_추가하지_않고_A에서_B로_바뀐_뒤_A로_돌아오면_기록한다() {
        var ref = store.current("204000057").orElseThrow();
        assertThat(store.publish(ref, read(0, 127.1))).isEqualTo(RouteGeometryStore.Publication.UPDATED);
        assertThat(store.publish(ref, read(1, 127.1))).isEqualTo(RouteGeometryStore.Publication.UNCHANGED);
        assertThat(count()).isEqualTo(1);
        store.publish(ref, read(2, 127.2));
        store.publish(ref, read(3, 127.1));
        assertThat(count()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT points->1->>'x' FROM route_geometry ORDER BY received_at DESC LIMIT 1")
            .query(String.class).single()).isEqualTo("127.1");
    }

    @Test
    void 닫힌_판본과_늦게_완료된_이전_응답은_현재_형상을_덮지_않는다() {
        var ref = store.current("204000057").orElseThrow();
        store.publish(ref, read(2, 127.2));
        assertThat(store.publish(ref, read(1, 127.1))).isEqualTo(RouteGeometryStore.Publication.UNCHANGED);
        assertThat(count()).isEqualTo(1);
        jdbc.sql("UPDATE route_version SET valid_to='2026-10-14T00:00:00Z' WHERE id=?").param(ref.versionId()).update();
        assertThat(store.publish(ref, read(3, 127.3))).isEqualTo(RouteGeometryStore.Publication.REFERENCE_CHANGED);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void 같은_정상변경이_겹쳐도_한번만_저장한다() throws Exception {
        var ref = store.current("204000057").orElseThrow();
        try (var callers = Executors.newFixedThreadPool(2)) {
            var first = callers.submit(() -> store.publish(ref, read(1, 127.1)));
            var second = callers.submit(() -> store.publish(ref, read(2, 127.1)));
            assertThat(List.of(first.get(), second.get())).contains(RouteGeometryStore.Publication.UPDATED);
        }
        assertThat(count()).isEqualTo(1);
    }
}
