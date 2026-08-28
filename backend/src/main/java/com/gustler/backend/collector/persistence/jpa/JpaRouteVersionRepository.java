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
            .map(version -> new StoredRouteVersion(version.getId(), version.getValidFrom(), version.content()));
    }

    @Override
    public void closeAt(
        final long routeVersionId,
        OffsetDateTime closedAt
    ) {
        findVersionOrThrow(routeVersionId).closeAt(closedAt);
        routeVersionEntityRepository.flush();
    }

    @Override
    public long openNewVersion(
        final long routeId,
        RouteStops routeStops,
        RouteVersionContent content,
        OffsetDateTime openedAt
    ) {
        RouteVersionJpaEntity newVersion = routeVersionEntityRepository.save(
            new RouteVersionJpaEntity(routeId, routeStops.turnSequence(), content, openedAt));
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
        findVersionOrThrow(routeVersionId).revise(timetable);
    }

    private RouteVersionJpaEntity findVersionOrThrow(
        final long routeVersionId
    ) {
        return routeVersionEntityRepository.findById(routeVersionId)
            .orElseThrow(() -> new IllegalStateException("판본 %d 가 없다".formatted(routeVersionId)));
    }
}
