package com.gustler.backend.collector.persistence.jpa;

import com.gustler.backend.collector.RouteStops;
import com.gustler.backend.collector.RouteTimetable;
import com.gustler.backend.collector.RouteVersionContent;
import com.gustler.backend.collector.RouteVersionRepository;
import com.gustler.backend.collector.StoredRouteVersion;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class JpaRouteVersionRepository implements RouteVersionRepository {

    private final RouteVersionEntityRepository routeVersionEntityRepository;
    private final RouteStopEntityRepository routeStopEntityRepository;

    public JpaRouteVersionRepository(
        RouteVersionEntityRepository routeVersionEntityRepository,
        RouteStopEntityRepository routeStopEntityRepository
    ) {
        this.routeVersionEntityRepository = routeVersionEntityRepository;
        this.routeStopEntityRepository = routeStopEntityRepository;
    }

    @Override
    public Optional<StoredRouteVersion> findLatestOf(
        final long routeId
    ) {
        return routeVersionEntityRepository.findFirstByRouteIdOrderByValidFromDescIdDesc(routeId)
            .map(version -> new StoredRouteVersion(version.getId(), version.content()));
    }

    @Override
    public long openNewVersion(
        final long routeId,
        RouteStops routeStops,
        RouteTimetable timetable,
        OffsetDateTime openedAt
    ) {
        closePreviousBeforeOpeningNew(routeId, openedAt);

        RouteVersionJpaEntity newVersion = routeVersionEntityRepository.save(new RouteVersionJpaEntity(
            routeId,
            routeStops.turnSequence(),
            RouteVersionContent.of(routeStops, timetable),
            openedAt));
        routeStopEntityRepository.saveAll(routeStops.stops().stream()
            .map(stop -> new RouteStopJpaEntity(newVersion.getId(), stop))
            .toList());
        return newVersion.getId();
    }

    @Override
    public void reviseTimetableOf(
        final long routeVersionId,
        RouteTimetable timetable
    ) {
        routeVersionEntityRepository.findById(routeVersionId)
            .orElseThrow()
            .revise(timetable);
    }

    private void closePreviousBeforeOpeningNew(
        final long routeId,
        OffsetDateTime openedAt
    ) {
        routeVersionEntityRepository.findFirstByRouteIdOrderByValidFromDescIdDesc(routeId)
            .ifPresent(previousVersion -> {
                previousVersion.closeAt(openedAt);
                routeVersionEntityRepository.flush();
            });
    }
}
