package com.gustler.backend.api.arrival;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record StationArrivalsResponse(String stationId, Instant generatedAt, String status, String reason,
    Snapshot snapshot, Refresh refresh, List<Route> routes) {
    public StationArrivalsResponse { routes = List.copyOf(routes); }
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
