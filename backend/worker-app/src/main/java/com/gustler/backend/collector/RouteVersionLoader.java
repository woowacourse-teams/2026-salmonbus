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
        RouteVersionContent incomingContent = RouteVersionContent.of(routeStops, timetable);

        return routeVersionRepository.findLatestOf(routeId)
            .map(latestVersion -> continueFrom(latestVersion, routeId, routeStops, incomingContent, readAt))
            .orElseGet(() -> routeVersionRepository.openNewVersion(routeId, routeStops, incomingContent, readAt));
    }

    private long continueFrom(
        StoredRouteVersion latestVersion,
        final long routeId,
        RouteStops routeStops,
        RouteVersionContent incomingContent,
        OffsetDateTime readAt
    ) {
        return switch (latestVersion.content().decideFor(incomingContent)) {
            case OPEN_NEW_VERSION -> openNewVersionAfter(latestVersion, routeId, routeStops, incomingContent, readAt);
            case REVISE_TIMETABLE -> reviseTimetableOf(latestVersion, incomingContent.timetable());
            case KEEP_CURRENT_VERSION -> latestVersion.id();
        };
    }

    private long openNewVersionAfter(
        StoredRouteVersion latestVersion,
        final long routeId,
        RouteStops routeStops,
        RouteVersionContent incomingContent,
        OffsetDateTime readAt
    ) {
        latestVersion.requireOpenableAt(readAt);
        routeVersionRepository.closeAt(latestVersion.id(), readAt);
        return routeVersionRepository.openNewVersion(routeId, routeStops, incomingContent, readAt);
    }

    private long reviseTimetableOf(
        StoredRouteVersion latestVersion,
        RouteTimetable timetable
    ) {
        routeVersionRepository.reviseTimetableOf(latestVersion.id(), timetable);
        return latestVersion.id();
    }
}
