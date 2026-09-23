package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.domain.model.RouteStop;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.publication.RouteVersionRepository;
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
public class JdbcRouteVersionRepository implements RouteVersionRepository {

    /**
     * 유효 기간이 안 닫힌 판본. 노선 하나에 valid_to 가 NULL 인 행이 지금 쓰는 판본이다.
     *
     * <p>노선마다 하나로 좁히는 조건을 안 건다. ex_route_version_no_overlap 이 한 노선의
     * 유효 기간이 겹치는 것을 이미 막아서, 열려 있는 행은 노선마다 많아야 하나다.
     *
     * <p>id 오름차순으로 준다. 배치가 판본을 도는 차례가 실행마다 달라지면 재현이 안 된다.
     */
    private static final String SELECT_ACTIVE_VERSION_IDS = """
        SELECT id
        FROM route_version
        WHERE valid_to IS NULL
        ORDER BY id
        """;

    /**
     * 한 판본의 정류장 전부. 순번 오름차순이다.
     *
     * <p>승차할 수 없는 경유 지점도 빼지 않고 준다. 예보 대상에서 거르는 판정은 RouteStops 가
     * boarding_allowed 를 보고 한다. 여기서 걸러 버리면 도메인이 그 자리를 아예 못 본다.
     *
     * <p>name 과 direction 은 안 읽는다. forecasting 의 RouteStop 이 그 둘을 들지 않는다.
     */
    private static final String SELECT_STOPS_OF_VERSION = """
        SELECT route_version_id, stop_order, stop_id, boarding_allowed
        FROM route_stop
        WHERE route_version_id = :routeVersionId
        ORDER BY stop_order
        """;

    /** 그 판본이 어느 Open API 노선인가. 계수 묶음이 노선 이름으로 계수를 고르는 데 쓴다. */
    private static final String SELECT_SOURCE_ROUTE_ID = """
        SELECT route.public_route_id
        FROM route_version
        JOIN route ON route.id = route_version.route_id
        WHERE route_version.id = :routeVersionId
        """;

    private final JdbcClient jdbcClient;

    public JdbcRouteVersionRepository(
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
                resultSet.getBoolean("boarding_allowed")))
            .list();
        return new RouteStops(routeVersionId, sourceRouteIdOf(routeVersionId), stops);
    }

    private String sourceRouteIdOf(
        final long routeVersionId
    ) {
        return jdbcClient.sql(SELECT_SOURCE_ROUTE_ID)
            .param("routeVersionId", routeVersionId)
            .query(String.class)
            .single();
    }
}
