package com.gustler.backend.routecatalog.application;

import com.gustler.backend.routecatalog.domain.Route;
import com.gustler.backend.routecatalog.domain.RouteRepository;
import com.gustler.backend.routecatalog.domain.RouteStopLocationStore;
import com.gustler.backend.routecatalog.domain.RouteStopLocationStore.Changes;
import com.gustler.backend.routecatalog.domain.RouteStops;
import com.gustler.backend.routecatalog.domain.RouteTimetable;
import com.gustler.backend.routecatalog.domain.RouteVersion;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 노선 행 잠금 아래 판본·시간표·좌표 관측을 반영한다.
 * 변경 알림은 실제 커밋 뒤에 기록한다.
 */
@Component
public class RouteVersionLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(RouteVersionLoader.class);
    private final RouteRepository routeRepository;
    private final RouteStopLocationStore locations;

    public RouteVersionLoader(RouteRepository routeRepository, RouteStopLocationStore locations) {
        this.routeRepository = routeRepository;
        this.locations = locations;
    }

    /**
     * 노선 행 잠금 아래 현재 판본을 유지·수정하거나 새 판본을 저장한다. 원천 HTTP 호출은 이 경계 밖에서 수행한다.
     */
    @Transactional(readOnly = false)
    public long load(
        final long routeId,
        RouteStops stops,
        RouteTimetable timetable,
        OffsetDateTime readAt
    ) {
        Route route = routeRepository.findByIdForUpdate(routeId);
        Optional<RouteVersion> previous = route.latestVersion();
        route.accept(stops, timetable, readAt);
        final long versionId = routeRepository.save(route);
        Changes changes = locations.record(versionId, stops, readAt);
        final boolean versionChanged = previous.isEmpty() || previous.get().id() != versionId;
        final boolean timetableChanged = previous.isPresent()
            && !previous.get().content().timetable().equals(route.currentVersion().content().timetable());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (versionChanged || timetableChanged) {
                    LOGGER.info("event=route_catalog_changed routeId={} previousRouteVersionId={} routeVersionId={} "
                        + "change={} stopCount={} observedAt={}",
                        routeId, previous.map(version -> version.id()).orElse(null), versionId,
                        changeReason(versionChanged, previous),
                        stops.stops().size(), readAt);
                }
                if (changes.hasWrites()) {
                    LOGGER.info("event=route_coordinates_recorded routeId={} routeVersionId={} firstCaptured={} "
                            + "changed={} missing={} observedAt={}",
                        routeId, versionId, changes.firstCaptured(), changes.changed(), changes.missing(), readAt);
                }
                if (changes.missing() > 0) {
                    LOGGER.warn("event=route_coordinates_missing routeId={} routeVersionId={} count={}",
                        routeId, versionId, changes.missing());
                }
            }
        });
        return versionId;
    }

    private static String changeReason(final boolean versionChanged, Optional<RouteVersion> previous) {
        if (!versionChanged) {
            return "TIMETABLE";
        }
        if (previous.isEmpty()) {
            return "INITIAL";
        }
        return "STOPS";
    }
}
