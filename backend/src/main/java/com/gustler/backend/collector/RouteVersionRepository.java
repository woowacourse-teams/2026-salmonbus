package com.gustler.backend.collector;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface RouteVersionRepository {

    Optional<StoredRouteVersion> findLatestOf(
        long routeId
    );

    long openNewVersion(
        long routeId,
        RouteStops routeStops,
        RouteTimetable timetable,
        OffsetDateTime openedAt
    );

    void reviseTimetableOf(
        long routeVersionId,
        RouteTimetable timetable
    );
}
