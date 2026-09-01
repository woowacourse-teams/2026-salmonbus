package com.gustler.backend.collector.persistence.jdbc;

import com.gustler.backend.collector.CurrentRouteVersionRepository;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * route 를 조인해야 해서 JdbcClient 로 읽는다. collector 에 route 엔티티가 없다.
 * valid_to 가 비어 있는 판본이 지금 쓰는 판본이다.
 */
@Repository
public class JdbcCurrentRouteVersionRepository implements CurrentRouteVersionRepository {

    private static final String FIND_CURRENT_VERSION = """
        SELECT route_version.id
        FROM route_version
        JOIN route ON route.id = route_version.route_id
        WHERE route.public_route_id = ? AND route_version.valid_to IS NULL
        ORDER BY route_version.valid_from DESC, route_version.id DESC
        LIMIT 1
        """;

    private final JdbcClient jdbcClient;

    public JdbcCurrentRouteVersionRepository(
        JdbcClient jdbcClient
    ) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public OptionalLong findIdOf(
        String upstreamRouteId
    ) {
        Optional<Long> found = jdbcClient.sql(FIND_CURRENT_VERSION)
            .param(upstreamRouteId)
            .query(Long.class)
            .optional();
        return found.map(OptionalLong::of).orElseGet(OptionalLong::empty);
    }
}
