package com.gustler.backend.api.arrival.dto;

import com.gustler.backend.api.arrival.application.StationArrivalsOverview;
import com.gustler.backend.api.arrival.domain.ArrivalPrediction;
import com.gustler.backend.api.arrival.domain.ArrivalVisit;
import com.gustler.backend.api.arrival.domain.RouteArrivals;
import com.gustler.backend.arrival.contract.ArrivalRefresh;
import com.gustler.backend.arrival.contract.ArrivalSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record StationArrivalsResponse(String stationId, Instant generatedAt, String status, String reason,
    Snapshot snapshot, Refresh refresh, List<Route> routes) {
    public StationArrivalsResponse { routes = List.copyOf(routes); }

    public static StationArrivalsResponse from(StationArrivalsOverview overview) {
        return new StationArrivalsResponse(overview.stationId(), overview.generatedAt(), overview.status().name(),
            overview.reason(), snapshotOf(overview.snapshot()), refreshOf(overview.refresh()),
            overview.routes().stream().map(StationArrivalsResponse::routeOf).toList());
    }

    private static Snapshot snapshotOf(ArrivalSnapshot snapshot) {
        return snapshot == null ? null : new Snapshot(snapshot.id(), snapshot.receivedAt(), null,
            snapshot.sourceQueryTime(), snapshot.refreshAfterAt(), snapshot.staleAt());
    }

    private static Refresh refreshOf(ArrivalRefresh value) {
        String status = switch (value.status()) {
            case ACCEPTED, IN_PROGRESS -> "RUNNING";
            case DEFERRED -> "NOT_DUE".equals(value.reason()) ? "IDLE" : "BLOCKED";
            case IDLE -> "IDLE";
            case UNAVAILABLE -> "FAILED";
        };
        return new Refresh(status, value.reason(), value.suggestedRefreshAt());
    }

    private static Route routeOf(RouteArrivals route) {
        ArrivalVisit visit = route.targetVisit();
        TargetVisit target = new TargetVisit(visit == null ? null : Long.toString(visit.referenceVersionId()),
            visit == null ? null : visit.stopSequence(), route.sourceStopOrder(),
            visit == null ? "UNVERIFIED" : "MATCHED", visit == null ? null : visit.direction());
        return new Route(route.routeId(), route.name(), target, route.status().name(), route.reason(),
            route.arrivals().stream().map(StationArrivalsResponse::vehicleOf).toList());
    }

    private static Vehicle vehicleOf(ArrivalPrediction prediction) {
        var source = prediction.source();
        Eta eta = new Eta(prediction.available() ? "AVAILABLE" : "UNAVAILABLE",
            prediction.available() ? null : "PREDICTION_ELAPSED", prediction.resolution(),
            prediction.estimateBaseAt(), "RECEIVED_AT_APPROXIMATION", prediction.expectedArrivalAt(),
            prediction.remainingSeconds());
        return new Vehicle(source.vehicleId(), source.ordinal(), source.remainingStops(), eta,
            new Seat(source.remainingSeats() == null ? "UNKNOWN" : "EXACT", source.remainingSeats(),
                source.seatUnknownReason()), source.congestion(), source.vehicleType(), source.taglessSupported());
    }
    public record Snapshot(UUID snapshotId, Instant receivedAt, Instant sourceObservedAt,
                           String sourceQueryTime, Instant refreshAfterAt, Instant staleAt) { }
    public record Refresh(String status, String reason, Instant suggestedRefreshAt) { }
    public record TargetVisit(String referenceVersionId, Integer stopSequence, int sourceStopOrder,
                              String matchStatus, String direction) { }
    public record Route(String routeId, String name, TargetVisit targetVisit, String status, String reason,
                        List<Vehicle> arrivals) {
        public Route { arrivals = List.copyOf(arrivals); }
    }
    public record Vehicle(String vehicleId, int ordinal, Integer remainingStops, Eta eta,
                          Seat currentSeat, Integer congestionCode, Integer vehicleTypeCode,
                          Boolean taglessSupported) { }
    public record Eta(String status, String reason, String resolution, Instant estimateBaseAt,
                      String baseKind, Instant expectedArrivalAt, Long remainingSeconds) { }
    public record Seat(String kind, Integer remaining, String reason) { }
}
