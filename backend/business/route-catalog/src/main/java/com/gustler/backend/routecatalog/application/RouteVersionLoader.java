package com.gustler.backend.routecatalog.application;

import com.gustler.backend.routecatalog.domain.Route;
import com.gustler.backend.routecatalog.domain.RouteStopLocationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.gustler.backend.routecatalog.domain.RouteRepository;
import com.gustler.backend.routecatalog.domain.RouteStops;
import com.gustler.backend.routecatalog.domain.RouteTimetable;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class RouteVersionLoader {

    private static final Logger log = LoggerFactory.getLogger(RouteVersionLoader.class);
    private final RouteRepository routeRepository;
    private final RouteStopLocationStore locations;

    public RouteVersionLoader(RouteRepository routeRepository, RouteStopLocationStore locations) {
        this.routeRepository = routeRepository;
        this.locations = locations;
    }

    @Transactional
    public long load(long routeId, RouteStops stops, RouteTimetable timetable, OffsetDateTime readAt) {
        Route route = routeRepository.findByIdForUpdate(routeId);
        var previous = route.latestVersion();
        route.accept(stops, timetable, readAt);
        long versionId = routeRepository.save(route);
        var changes = locations.record(versionId, stops, readAt);
        boolean versionChanged = previous.isEmpty() || previous.get().id() != versionId;
        boolean timetableChanged = previous.isPresent()
            && !previous.get().content().timetable().equals(route.currentVersion().content().timetable());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (versionChanged || timetableChanged) {
                    log.info("event=route_catalog_changed routeId={} previousRouteVersionId={} routeVersionId={} "
                            + "change={} stopCount={} observedAt={}",
                        routeId, previous.map(version -> version.id()).orElse(null), versionId,
                        versionChanged ? (previous.isEmpty() ? "INITIAL" : "STOPS") : "TIMETABLE",
                        stops.stops().size(), readAt);
                }
                if (changes.hasWrites()) {
                    log.info("event=route_coordinates_recorded routeId={} routeVersionId={} firstCaptured={} "
                            + "changed={} missing={} observedAt={}",
                        routeId, versionId, changes.firstCaptured(), changes.changed(), changes.missing(), readAt);
                }
                if (changes.missing() > 0) {
                    log.warn("event=route_coordinates_missing routeId={} routeVersionId={} count={}",
                        routeId, versionId, changes.missing());
                }
            }
        });
        return versionId;
    }
}
