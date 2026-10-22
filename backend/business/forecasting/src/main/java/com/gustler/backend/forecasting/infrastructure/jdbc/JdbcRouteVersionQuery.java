package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.domain.model.RouteDirection;
import com.gustler.backend.forecasting.domain.model.RouteStop;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.publication.RouteStopsQuery;
import com.gustler.backend.forecasting.domain.route.RouteVersionQuery;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 노선 판본과 경유 정류장을 SQL 로 직접 읽는다. route-catalog 의 JPA 엔티티를 쓰지 않는다.
 *
 * <p>route_version 과 route_stop 을 매핑한 엔티티는 route-catalog 가 자기 컨텍스트에서 쓰려고 만든 것이다.
 * 예보가 그 엔티티를 가져다 쓰면 컨텍스트 경계가 없어지므로 필요한 열만 SQL 로 읽는다.
 */
@Repository
public class JdbcRouteVersionQuery implements RouteVersionQuery, RouteStopsQuery {
    /**
     * 유효 기간이 종료되지 않은 판본이다. 노선별로 valid_to 가 NULL 인 행이 현재 사용하는 판본이다.
     *
     * <p>노선마다 하나로 제한하는 조건은 추가하지 않는다. ex_route_version_no_overlap 이 한 노선의
     * 유효 기간 중복을 이미 막으므로 열려 있는 행은 노선마다 최대 하나다.
     *
     * <p>id 오름차순으로 반환한다. 배치의 판본 처리 순서가 실행마다 달라지면 재현할 수 없다.
     */
    private static final String SELECT_ACTIVE_VERSION_IDS = """
        SELECT id
        FROM route_version
        WHERE valid_to IS NULL
        ORDER BY id
        """;

    /**
     * 한 판본의 전체 정류장을 순번 오름차순으로 반환한다.
     *
     * <p>승차할 수 없는 경유 지점도 포함한다. 예보 대상 제외는 RouteStops 가
     * boarding_allowed 를 보고 판정한다. 여기서 제외하면 도메인이 해당 정류장을 확인할 수 없다.
     *
     * <p>정류장 이름 대신 순번과 실제 UP/DOWN 방향을 예보 입력에 전달한다.
     */
    private static final String SELECT_STOPS_OF_VERSION = """
        SELECT route_version_id, stop_order, stop_id, boarding_allowed, direction
        FROM route_stop
        WHERE route_version_id = :routeVersionId
        ORDER BY stop_order
        """;

    /** 모델 선택용 API 노선 ID와 로그 표시용 실제 노선명을 기존 조회에서 함께 읽는다. */
    private static final String SELECT_SOURCE_ROUTE_ID = """
        SELECT route.public_route_id, route.display_name
        FROM route_version
        JOIN route ON route.id = route_version.route_id
        WHERE route_version.id = :routeVersionId
        """;

    private final JdbcClient jdbcClient;

    public JdbcRouteVersionQuery(
        JdbcClient jdbcClient
    ) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<Long> findActiveVersionIds() {
        return jdbcClient.sql(SELECT_ACTIVE_VERSION_IDS)
            .query(Long.class)
            .list();
    }

    @Override
    public RouteStops readStops(
        final long routeVersionId
    ) {
        List<RouteStop> stops = jdbcClient.sql(SELECT_STOPS_OF_VERSION)
            .param("routeVersionId", routeVersionId)
            .query((resultSet, rowNumber) -> new RouteStop(
                resultSet.getLong("route_version_id"),
                resultSet.getInt("stop_order"),
                resultSet.getString("stop_id"),
                resultSet.getBoolean("boarding_allowed"),
                RouteDirection.valueOf(resultSet.getString("direction"))))
            .list();
        return jdbcClient.sql(SELECT_SOURCE_ROUTE_ID)
            .param("routeVersionId", routeVersionId)
            .query((rs, row) -> new RouteStops(routeVersionId, rs.getString("public_route_id"),
                stops, rs.getString("display_name")))
            .single();
    }
}
