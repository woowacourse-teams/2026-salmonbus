package com.gustler.backend.routecatalog.application;

import com.gustler.backend.routecatalog.domain.Route;
import com.gustler.backend.routecatalog.domain.RouteRepository;
import com.gustler.backend.routecatalog.domain.RouteStops;
import com.gustler.backend.routecatalog.domain.RouteTimetable;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class RouteVersionLoader {

    private final RouteRepository routeRepository;

    public RouteVersionLoader(RouteRepository routeRepository) {
        this.routeRepository = routeRepository;
    }

    @Transactional
    public long load(long routeId, RouteStops stops, RouteTimetable timetable, OffsetDateTime readAt) {
        Route route = routeRepository.findByIdForUpdate(routeId);
        route.accept(stops, timetable, readAt);
        return routeRepository.save(route);
    }
}
