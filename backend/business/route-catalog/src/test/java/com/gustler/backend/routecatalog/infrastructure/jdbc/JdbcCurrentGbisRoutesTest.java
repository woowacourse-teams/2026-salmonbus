package com.gustler.backend.routecatalog.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.routecatalog.domain.CurrentRouteVersionQuery;
import com.gustler.backend.support.PostgresTestContainer;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(classes = JdbcCurrentGbisRoutesTest.Config.class)
class JdbcCurrentGbisRoutesTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({PostgresTestContainer.class, JdbcCurrentRouteVersionQuery.class})
    static class Config { }

    @Autowired CurrentRouteVersionQuery routes;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE route CASCADE").update();
    }

    @Test
    void 현재판본이_있는_GBIS_공개ID만_반환하고_추가와_폐쇄를_즉시_반영한다() {
        long current = route("204000002", "GBIS", "upstream-2");
        version(current, false);
        long closed = route("204000003", "GBIS", "upstream-3");
        version(closed, true);
        route("204000004", "GBIS", "upstream-4");
        version(route("204000005", "OTHER", "upstream-5"), false);

        assertThat(routes.findCurrentGbisRouteIds()).containsExactly("204000002");

        long added = route("204000001", "GBIS", "upstream-1");
        version(added, false);
        assertThat(routes.findCurrentGbisRouteIds()).containsExactly("204000001", "204000002");

        jdbc.sql("UPDATE route_version SET valid_to = ? WHERE route_id = ? AND valid_to IS NULL")
            .params(OffsetDateTime.parse("2026-10-16T01:00:00Z"), current).update();
        assertThat(routes.findCurrentGbisRouteIds()).containsExactly("204000001");
    }

    private long route(String publicId, String source, String sourceId) {
        return jdbc.sql("""
            INSERT INTO route(public_route_id, source_id, source_route_id, display_name, start_stop_name, end_stop_name)
            VALUES (?, ?, ?, 'route', 'start', 'end') RETURNING id
            """).params(publicId, source, sourceId).query(Long.class).single();
    }

    private void version(long route, boolean closed) {
        jdbc.sql("""
            INSERT INTO route_version(route_id, content_digest, valid_from, valid_to)
            VALUES (?, ?, ?, ?)
            """).params(route, "a".repeat(64), OffsetDateTime.parse("2026-10-15T00:00:00Z"),
                closed ? OffsetDateTime.parse("2026-10-16T00:00:00Z") : null).update();
    }
}
