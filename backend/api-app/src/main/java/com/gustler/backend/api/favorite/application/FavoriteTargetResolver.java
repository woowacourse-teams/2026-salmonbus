package com.gustler.backend.api.favorite.application;

import com.gustler.backend.api.favorite.FavoriteTargetAmbiguousException;
import com.gustler.backend.api.favorite.FavoriteTargetUnavailableException;
import com.gustler.backend.api.favorite.domain.FavoriteTarget;
import com.gustler.backend.api.route.application.RouteGeometryQueryRepository;
import org.springframework.stereotype.Component;

@Component
public class FavoriteTargetResolver {
    private final RouteGeometryQueryRepository routes;

    public FavoriteTargetResolver(RouteGeometryQueryRepository routes) {
        this.routes = routes;
    }

    public FavoriteTarget requireAvailable(FavoriteTarget target) {
        var reference = routes.reference(target.routeId()).orElseThrow(FavoriteTargetUnavailableException::new);
        long visits = routes.stops(reference.versionId()).stream()
            .filter(stop -> stop.stationId().equals(target.stopId()))
            .filter(stop -> stop.direction().equals(target.direction().name()))
            .filter(stop -> stop.boardingAllowed())
            .count();
        if (visits == 0) {
            throw new FavoriteTargetUnavailableException();
        }
        if (visits > 1) {
            throw new FavoriteTargetAmbiguousException();
        }
        return target;
    }
}
