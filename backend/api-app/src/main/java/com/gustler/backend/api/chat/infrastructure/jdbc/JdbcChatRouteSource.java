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

/**
 * 현재 노선과 정류장 표시명을 채팅방 카탈로그의 입력으로 읽는다.
 */
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
                route.addStopName(stopName);
            });
            return routes.values()
                .stream()
                .map(RouteRows::toRoute)
                .toList();
        } catch (DataAccessResourceFailureException exception) {
            throw new ServiceUnavailableException();
        }
    }

    /**
     * SQL 행의 노선별 누적 상태다. 조회 순서대로 모은 이름은 불변 ChatRoute를 만들 때 복사된다.
     */
    private static final class RouteRows {
        private final String routeId;
        private final String displayName;
        private final List<String> stopNames = new ArrayList<>();

        private RouteRows(String routeId, String displayName) {
            this.routeId = routeId;
            this.displayName = displayName;
        }

        private void addStopName(String stopName) {
            if (stopName != null) {
                stopNames.add(stopName);
            }
        }

        private ChatRoute toRoute() {
            return new ChatRoute(routeId, displayName, stopNames);
        }
    }
}
