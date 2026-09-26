package com.gustler.backend.routecatalog.domain;

import java.time.OffsetDateTime;
import java.util.Optional;

/** 한 노선의 버전 교체를 결정한다. 과거 버전 전체를 로딩할 필요는 없다. */
public final class Route {

    private final long id;
    private RouteVersion currentVersion;
    private RouteVersion closedVersion;
    private RouteStops newVersionStops;

    public Route(long id, RouteVersion latestVersion) {
        if (id < 1) {
            throw new IllegalArgumentException("노선 ID는 양수여야 한다");
        }
        this.id = id;
        this.currentVersion = latestVersion;
    }

    public void accept(RouteStops stops, RouteTimetable timetable, OffsetDateTime at) {
        RouteVersionContent incoming = RouteVersionContent.of(stops, timetable);
        if (currentVersion == null) {
            openVersion(stops, incoming, at);
            return;
        }
        currentVersion.requireCurrent();
        switch (currentVersion.content().decideFor(incoming)) {
            case OPEN_NEW_VERSION -> {
                closedVersion = currentVersion.closeAt(at);
                openVersion(stops, incoming, at);
            }
            case REVISE_TIMETABLE -> currentVersion = currentVersion.reviseTimetable(timetable);
            case KEEP_CURRENT_VERSION -> { }
        }
    }

    private void openVersion(RouteStops stops, RouteVersionContent content, OffsetDateTime at) {
        currentVersion = RouteVersion.open(content, at);
        newVersionStops = stops;
    }

    public long id() {
        return id;
    }

    public RouteVersion currentVersion() {
        if (currentVersion == null) {
            throw new IllegalStateException("노선 버전을 먼저 열어야 한다");
        }
        return currentVersion;
    }

    public Optional<RouteVersion> closedVersion() {
        return Optional.ofNullable(closedVersion);
    }

    public RouteStops newVersionStops() {
        if (newVersionStops == null) {
            throw new IllegalStateException("새 노선 버전의 정류장이 없다");
        }
        return newVersionStops;
    }
}
