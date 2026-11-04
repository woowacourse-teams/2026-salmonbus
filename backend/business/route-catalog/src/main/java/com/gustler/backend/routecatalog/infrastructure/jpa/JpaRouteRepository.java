package com.gustler.backend.routecatalog.infrastructure.jpa;

import com.gustler.backend.routecatalog.domain.Route;
import com.gustler.backend.routecatalog.domain.RouteRepository;
import com.gustler.backend.routecatalog.domain.RouteStop;
import com.gustler.backend.routecatalog.domain.RouteStops;
import com.gustler.backend.routecatalog.domain.RouteVersion;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 필수 노선을 잠가 읽고 도메인이 결정한 판본·시간표 변경을 저장한다.
 */
@Repository
@Transactional(readOnly = false, propagation = Propagation.MANDATORY)
public class JpaRouteRepository implements RouteRepository {
    private final JdbcClient jdbc;
    private final RouteVersionEntityRepository versions;
    private final RouteStopEntityRepository stops;

    public JpaRouteRepository(JdbcClient jdbc, RouteVersionEntityRepository versions,
        RouteStopEntityRepository stops) {
        this.jdbc = jdbc;
        this.versions = versions;
        this.stops = stops;
    }

    @Override
    public Route findByIdForUpdate(final long routeId) {
        jdbc.sql("SELECT id FROM route WHERE id = ? FOR UPDATE")
            .param(routeId).query(Long.class).optional()
            .orElseThrow(() -> new IllegalStateException("노선 %d가 없다".formatted(routeId)));
        RouteVersion latest = versions.findFirstByRouteIdOrderByValidFromDescIdDesc(routeId)
            .map(RouteVersionJpaEntity::toDomain).orElse(null);
        return new Route(routeId, latest);
    }

    @Override
    public long save(Route route) {
        route.closedVersion().ifPresent(closed -> {
            versionOf(closed.id()).apply(closed);
            // 기간 겹침 제약을 검사하기 전에 직전 버전의 종료가 DB에 반영돼야 한다.
            versions.flush();
        });
        RouteVersion current = route.currentVersion();
        if (current.id() != null) {
            versionOf(current.id()).apply(current);
            versions.flush();
            return current.id();
        }
        RouteVersionJpaEntity opened = versions.save(new RouteVersionJpaEntity(
            route.id(), route.newVersionStops().turnSequence(), current.content(), current.validFrom()));
        stops.saveAll(route.newVersionStops()
            .stops()
            .stream()
            .map(stop -> new RouteStopJpaEntity(opened.getId(), stop))
            .toList());
        versions.flush();
        return opened.getId();
    }

    /**
     * 순번·ID가 같은 행의 이름만 한 문장으로 고친다. 노선 행 잠금 아래에서 매 갱신마다 불리므로
     * 정류장 수만큼 왕복하지 않고, 이름이 이미 같은 행은 건드리지 않는다.
     */
    @Override
    public int renameStops(final long routeVersionId, RouteStops stops) {
        List<RouteStop> incoming = stops.stops();
        if (incoming.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
                UPDATE route_stop stop SET name = incoming.name
                FROM unnest(ARRAY[:orders]::int[], ARRAY[:ids]::text[], ARRAY[:names]::text[])
                    AS incoming(stop_order, stop_id, name)
                WHERE stop.route_version_id = :version
                  AND stop.stop_order = incoming.stop_order
                  AND stop.stop_id = incoming.stop_id
                  AND stop.name <> incoming.name
                """)
            .param("version", routeVersionId)
            .param("orders", incoming.stream().map(RouteStop::stopOrder).toList())
            .param("ids", incoming.stream().map(RouteStop::stopId).toList())
            .param("names", incoming.stream().map(RouteStop::name).toList())
            .update();
    }

    private RouteVersionJpaEntity versionOf(final long id) {
        return versions.findById(id)
            .orElseThrow(() -> new IllegalStateException("노선 버전 %d가 없다".formatted(id)));
    }
}
