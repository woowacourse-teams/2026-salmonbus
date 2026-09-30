package com.gustler.backend.api.chat.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.chat.application.ChatRoute;
import com.gustler.backend.support.IntegrationTest;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class JdbcChatRouteSourceTest {

    private static final OffsetDateTime FIRST_VERSION_AT = OffsetDateTime.parse("2026-08-01T00:00:00+09:00");
    private static final OffsetDateTime SECOND_VERSION_AT = OffsetDateTime.parse("2026-08-20T00:00:00+09:00");

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void 현재_판본의_노선과_정류장_이름을_노선_순서와_정류장_순서대로_읽는다() {
        // given
        final long route3330 = insertRoute("204000057", "3330");
        final long route9007 = insertRoute("204000070", "9007");
        final long expired = insertVersion(route3330, "1".repeat(64), FIRST_VERSION_AT, SECOND_VERSION_AT);
        insertStop(expired, 1, "옛 정류장");
        final long current3330 = insertVersion(route3330, "2".repeat(64), SECOND_VERSION_AT, null);
        insertStop(current3330, 2, "야탑역");
        insertStop(current3330, 1, "도촌동9단지앞");
        insertVersion(route9007, "3".repeat(64), SECOND_VERSION_AT, null);

        // when
        List<ChatRoute> routes = new JdbcChatRouteSource(jdbcClient).findCurrentRoutes();

        // then
        assertThat(routes).containsExactly(
            new ChatRoute("204000057", "3330", List.of("도촌동9단지앞", "야탑역")),
            new ChatRoute("204000070", "9007", List.of())
        );
    }

    private long insertRoute(final String sourceRouteId, final String displayName) {
        return jdbcClient.sql("""
                INSERT INTO route (
                    public_route_id, source_id, source_route_id,
                    display_name, start_stop_name, end_stop_name
                ) VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """)
            .params(sourceRouteId, "GBIS", sourceRouteId, displayName, "기점", "종점")
            .query(Long.class)
            .single();
    }

    private long insertVersion(
        final long routeId,
        final String contentDigest,
        final OffsetDateTime validFrom,
        final OffsetDateTime validTo
    ) {
        return jdbcClient.sql("""
                INSERT INTO route_version (route_id, content_digest, valid_from, valid_to)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """)
            .params(routeId, contentDigest, validFrom, validTo)
            .query(Long.class)
            .single();
    }

    private void insertStop(final long routeVersionId, final int stopOrder, final String name) {
        jdbcClient.sql("""
                INSERT INTO route_stop (
                    route_version_id, stop_order, stop_id,
                    name, direction, boarding_allowed
                ) VALUES (?, ?, ?, ?, ?, ?)
                """)
            .params(routeVersionId, stopOrder, "20500000" + stopOrder, name, "UP", true)
            .update();
    }
}
