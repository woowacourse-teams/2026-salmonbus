package com.gustler.backend.collector;

import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class RouteVersionLoader {

    private final RouteVersionRepository routeVersionRepository;

    public RouteVersionLoader(
        RouteVersionRepository routeVersionRepository
    ) {
        this.routeVersionRepository = routeVersionRepository;
    }

    @Transactional
    public long load(
        final long routeId,
        RouteStops routeStops,
        RouteTimetable timetable,
        OffsetDateTime readAt
    ) {
        return routeVersionRepository.findLatestOf(routeId)
            .map(latestVersion -> continueFrom(latestVersion, routeId, routeStops, timetable, readAt))
            .orElseGet(() -> routeVersionRepository.openNewVersion(routeId, routeStops, timetable, readAt));
    }

    private long continueFrom(
        StoredRouteVersion latestVersion,
        final long routeId,
        RouteStops routeStops,
        RouteTimetable timetable,
        OffsetDateTime readAt
    ) {
        return switch (latestVersion.content().decideFor(RouteVersionContent.of(routeStops, timetable))) {
            case OPEN_NEW_VERSION -> routeVersionRepository.openNewVersion(routeId, routeStops, timetable, readAt);
            case REVISE_TIMETABLE -> reviseTimetableOf(latestVersion, timetable);
            case KEEP_CURRENT_VERSION -> latestVersion.id();
        };
    }

    private long reviseTimetableOf(
        StoredRouteVersion latestVersion,
        RouteTimetable timetable
    ) {
        routeVersionRepository.reviseTimetableOf(latestVersion.id(), timetable);
        return latestVersion.id();
    }
}
