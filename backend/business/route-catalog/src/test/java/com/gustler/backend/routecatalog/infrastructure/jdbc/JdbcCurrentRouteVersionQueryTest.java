package com.gustler.backend.routecatalog.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.routecatalog.support.RouteCatalogIntegrationTest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** 실제 조회 SQL에서 원천 노선 식별자와 외부에 공개하는 식별자의 계약을 확인한다. */
@RouteCatalogIntegrationTest
@Transactional(readOnly = false)
class JdbcCurrentRouteVersionQueryTest {
    private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-11-23T00:00:00Z");

    @Autowired
    private JdbcCurrentRouteVersionQuery query;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void 공개_식별자가_달라도_원천_노선_식별자로_준비된_판본을_찾는다() {
        // given
        final long versionId = route("204000111", "GBIS", "204000057");

        // when
        OptionalLong found = query.findIdOf("204000057");

        // then
        assertThat(found).hasValue(versionId);
    }

    @Test
    void 다른_노선의_공개_식별자가_일치해도_원천_식별자가_맞는_판본을_찾는다() {
        // given
        final long versionId = route("204000111", "GBIS", "204000057");
        route("204000057", "GBIS", "204000222");

        // when
        OptionalLong found = query.findIdOf("204000057");

        // then
        assertThat(found).hasValue(versionId);
    }

    @Test
    void 다른_제공자의_같은_식별자는_GBIS_판본으로_사용하지_않는다() {
        // given
        route("204000057", "OTHER_PROVIDER", "204000057");

        // when
        OptionalLong found = query.findIdOf("204000057");

        // then
        assertThat(found).isEmpty();
    }

    @Test
    void 전체_GBIS_노선_목록은_기존대로_공개_식별자를_반환한다() {
        // given
        route("204000111", "GBIS", "204000057");
        route("204000222", "OTHER_PROVIDER", "204000222");

        // when
        List<String> routeIds = query.findCurrentGbisRouteIds();

        // then
        assertThat(routeIds).containsExactly("204000111");
    }

    private long route(String publicId, String sourceId, String sourceRouteId) {
        final long routeId = jdbc.sql("""
            INSERT INTO route(public_route_id, source_id, source_route_id, display_name, start_stop_name, end_stop_name)
            VALUES (?, ?, ?, '노선', '시작', '종료') RETURNING id
            """).params(publicId, sourceId, sourceRouteId).query(Long.class).single();
        return jdbc.sql("""
            INSERT INTO route_version(route_id, content_digest, valid_from)
            VALUES (?, ?, ?) RETURNING id
            """).params(routeId, "0".repeat(64), CREATED_AT).query(Long.class).single();
    }
}
