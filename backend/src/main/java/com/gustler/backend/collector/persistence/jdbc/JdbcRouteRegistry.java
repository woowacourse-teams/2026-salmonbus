package com.gustler.backend.collector.persistence.jdbc;

import com.gustler.backend.collector.RouteRegistry;
import com.gustler.backend.collector.UpstreamRoute;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * public_route_id 와 source_route_id 는 지금 같은 값이다. 공개 ID 가 확정되면 갈린다.
 * V1__collector.sql 15행이 그 상태를 적어두고 있다.
 */
@Repository
public class JdbcRouteRegistry implements RouteRegistry {

    private static final String SOURCE_ID = "GBIS";

    /** 표시명과 기점 · 종점 이름은 상류가 바꿀 수 있어 부를 때마다 맞춘다. 노선 자체는 그대로다. */
    private static final String REGISTER = """
        INSERT INTO route (
            public_route_id, source_id, source_route_id, display_name, start_stop_name, end_stop_name
        ) VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT ON CONSTRAINT ux_route_public_route_id DO UPDATE
            SET display_name = EXCLUDED.display_name,
                start_stop_name = EXCLUDED.start_stop_name,
                end_stop_name = EXCLUDED.end_stop_name
        RETURNING id
        """;

    private final JdbcClient jdbcClient;

    public JdbcRouteRegistry(
        JdbcClient jdbcClient
    ) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public long register(
        UpstreamRoute route
    ) {
        return jdbcClient.sql(REGISTER)
            .params(
                route.upstreamRouteId(), SOURCE_ID, route.upstreamRouteId(),
                route.displayName(), route.startStopName(), route.endStopName())
            .query(Long.class)
            .single();
    }
}
