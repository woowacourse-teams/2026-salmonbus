package com.gustler.backend.processor.persistence.jdbc;

import com.gustler.backend.processor.RouteStop;
import com.gustler.backend.processor.RouteStops;
import com.gustler.backend.processor.RouteVersionRepository;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 노선 판본과 경유 정류장을 SQL 로 직접 읽는다. collector 의 JPA 엔티티를 쓰지 않는다.
 *
 * <p>route_version 과 route_stop 을 매핑한 엔티티가 다른 패키지에 이미 있다. 여기서 엔티티를
 * 하나 더 만들면 Hibernate 가 같은 테이블을 두 번 매핑했다며 뜨지 않는다.
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
     * <p>name 과 direction 은 안 읽는다. processor 의 RouteStop 이 그 둘을 들지 않는다.
     */
    private static final String SELECT_STOPS_OF_VERSION = """
        SELECT route_version_id, stop_order, stop_id, boarding_allowed
        FROM route_stop
        WHERE route_version_id = :routeVersionId
        ORDER BY stop_order
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
        return new RouteStops(routeVersionId, stops);
    }
}
