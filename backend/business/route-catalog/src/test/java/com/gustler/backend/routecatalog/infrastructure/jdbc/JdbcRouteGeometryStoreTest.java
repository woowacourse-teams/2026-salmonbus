package com.gustler.backend.routecatalog.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.routecatalog.domain.geometry.RouteGeometrySource.Read;
import com.gustler.backend.routecatalog.domain.geometry.RouteGeometryStore;
import com.gustler.backend.routecatalog.domain.geometry.RouteGeometryStore.Publication;
import com.gustler.backend.routecatalog.domain.geometry.RouteGeometryStore.Reference;
import com.gustler.backend.routecatalog.domain.geometry.RouteShape;
import com.gustler.backend.routecatalog.domain.geometry.RouteShape.Point;
import com.gustler.backend.routecatalog.infrastructure.jdbc.JdbcRouteGeometryStoreTest.Config;
import com.gustler.backend.support.PostgresTestContainer;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
class JdbcRouteGeometryStoreTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({PostgresTestContainer.class, JdbcRouteGeometryStore.class})
    static class Config {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
    private static final OffsetDateTime BASE = OffsetDateTime.parse("2026-10-14T12:00:00Z");
    @Autowired
    RouteGeometryStore store;
    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE route CASCADE").update();
        final long route = jdbc.sql("""
            INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)
            VALUES ('204000057','GBIS','204000057','3330','A','B') RETURNING id
            """).query(Long.class).single();
        jdbc.sql("INSERT INTO route_version(route_id,content_digest,valid_from) VALUES (?,?,'2026-10-13T00:00:00Z')")
            .params(route, "a".repeat(64)).update();
    }

    private Read read(final int second, final double x) {
        return new Read(new RouteShape(List.of(new Point(127, 37),
            new Point(x, 37.1))), BASE.plusSeconds(second));
    }

    private int count() {
        return jdbc.sql("SELECT count(*) FROM route_geometry").query(Integer.class).single();
    }

    @Test
    void 같은_형상은_추가하지_않고_A에서_B로_바뀐_뒤_A로_돌아오면_기록한다() {
        // when
        Reference ref = store.current("204000057").orElseThrow();

        // then
        assertThat(store.publish(ref, read(0, 127.1))).isEqualTo(Publication.UPDATED);
        assertThat(store.publish(ref, read(1, 127.1))).isEqualTo(Publication.UNCHANGED);
        assertThat(count()).isEqualTo(1);
        store.publish(ref, read(2, 127.2));
        store.publish(ref, read(3, 127.1));
        assertThat(count()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT points->1->>'x' FROM route_geometry ORDER BY received_at DESC LIMIT 1")
            .query(String.class).single()).isEqualTo("127.1");
    }

    @Test
    void 닫힌_판본과_늦게_완료된_이전_응답은_현재_형상을_덮지_않는다() {
        // when
        Reference ref = store.current("204000057").orElseThrow();
        store.publish(ref, read(2, 127.2));

        // then
        assertThat(store.publish(ref, read(1, 127.1))).isEqualTo(Publication.UNCHANGED);
        assertThat(count()).isEqualTo(1);
        jdbc.sql("UPDATE route_version SET valid_to='2026-10-14T00:00:00Z' WHERE id=?").param(ref.versionId()).update();
        assertThat(store.publish(ref, read(3, 127.3))).isEqualTo(Publication.REFERENCE_CHANGED);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void 같은_정상변경이_겹쳐도_한번만_저장한다() throws Exception {
        // when
        Reference ref = store.current("204000057").orElseThrow();
        try (ExecutorService callers = Executors.newFixedThreadPool(2)) {
            Future<Publication> first = callers.submit(() -> store.publish(ref, read(1, 127.1)));
            Future<Publication> second = callers.submit(() -> store.publish(ref, read(2, 127.1)));

            // then
            assertThat(List.of(first.get(), second.get())).contains(Publication.UPDATED);
        }
        assertThat(count()).isEqualTo(1);
    }
}
