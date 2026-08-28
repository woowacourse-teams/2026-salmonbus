package com.gustler.backend.collector;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface RouteVersionRepository {

    Optional<StoredRouteVersion> findLatestOf(
        long routeId
    );

    void closeAt(
        long routeVersionId,
        OffsetDateTime closedAt
    );

    long openNewVersion(
        long routeId,
        RouteStops routeStops,
        RouteVersionContent content,
        OffsetDateTime openedAt
    );

    void reviseTimetableOf(
        long routeVersionId,
        RouteTimetable timetable
    );
}
