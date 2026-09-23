package com.gustler.backend.routecatalog.infrastructure.jpa;

import com.gustler.backend.routecatalog.domain.Route;
import com.gustler.backend.routecatalog.domain.RouteRepository;
import com.gustler.backend.routecatalog.domain.RouteVersion;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
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
    public Route findByIdForUpdate(long routeId) {
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
        stops.saveAll(route.newVersionStops().stops().stream()
            .map(stop -> new RouteStopJpaEntity(opened.getId(), stop)).toList());
        versions.flush();
        return opened.getId();
    }

    private RouteVersionJpaEntity versionOf(long id) {
        return versions.findById(id)
            .orElseThrow(() -> new IllegalStateException("노선 버전 %d가 없다".formatted(id)));
    }
}
