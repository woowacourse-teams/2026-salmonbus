package com.gustler.backend.api.chat.infrastructure.jdbc;

import com.gustler.backend.api.chat.application.ChatRoute;
import com.gustler.backend.api.chat.application.ChatRouteSource;
import com.gustler.backend.api.error.ServiceUnavailableException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcChatRouteSource implements ChatRouteSource {

    private static final String CURRENT_ROUTE_STOPS = """
        SELECT route.source_route_id, route.display_name, stop.name
        FROM route_version version
        JOIN route ON route.id = version.route_id
        LEFT JOIN route_stop stop ON stop.route_version_id = version.id
        WHERE version.valid_to IS NULL
        ORDER BY route.id, stop.stop_order
        """;

    private final JdbcClient jdbcClient;

    public JdbcChatRouteSource(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<ChatRoute> findCurrentRoutes() {
        try {
            Map<String, RouteRows> routes = new LinkedHashMap<>();
            jdbcClient.sql(CURRENT_ROUTE_STOPS).query(resultSet -> {
                String routeId = resultSet.getString("source_route_id");
                String displayName = resultSet.getString("display_name");
                String stopName = resultSet.getString("name");
                RouteRows route = routes.computeIfAbsent(routeId, id -> new RouteRows(id, displayName));
                if (stopName != null) {
                    route.stopNames().add(stopName);
                }
            });
            return routes.values().stream()
                .map(route -> new ChatRoute(route.routeId(), route.displayName(), route.stopNames()))
                .toList();
        } catch (DataAccessResourceFailureException exception) {
            throw new ServiceUnavailableException();
        }
    }

    private record RouteRows(String routeId, String displayName, List<String> stopNames) {

        RouteRows(String routeId, String displayName) {
            this(routeId, displayName, new ArrayList<>());
        }
    }
}
