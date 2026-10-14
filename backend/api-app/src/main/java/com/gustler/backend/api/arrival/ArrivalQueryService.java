package com.gustler.backend.api.arrival;

import com.gustler.backend.api.arrival.ArrivalReadRepository.Visit;
import com.gustler.backend.api.arrival.StationArrivalsResponse.*;
import com.gustler.backend.api.error.ErrorCode;
import com.gustler.backend.arrival.ArrivalRefresh;
import com.gustler.backend.arrival.ArrivalSnapshot;
import com.gustler.backend.arrival.ArrivalSnapshot.Prediction;
import com.gustler.backend.arrival.ArrivalSnapshot.RouteArrival;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ArrivalQueryService {
    private final ArrivalReadRepository repository;
    private final ArrivalRefreshGateway gateway;
    private final Clock clock;

    public ArrivalQueryService(ArrivalReadRepository repository, ArrivalRefreshGateway gateway, Clock clock) {
        this.repository = repository; this.gateway = gateway; this.clock = clock;
    }

    // 내부 HTTP 요청 중에 API의 DB 트랜잭션/연결을 유지하지 않는다.
    public StationArrivalsResponse get(ArrivalQuery query) {
        List<Visit> visits = repository.visits(query.stationId());
        if (visits.isEmpty()) throw new ArrivalQuery.Failure(ErrorCode.STATION_NOT_FOUND);
        if (query.referenceVersionId() != null && visits.stream().noneMatch(visit ->
            visit.routeId().equals(query.routeId()) && visit.referenceVersionId() == query.referenceVersionId()
                && visit.stopSequence() == query.stopSequence())) {
            throw new ArrivalQuery.Failure(ErrorCode.REFERENCE_VERSION_CONFLICT);
        }
        if (query.routeId() != null && visits.stream().noneMatch(visit -> visit.routeId().equals(query.routeId()))) {
            throw new ArrivalQuery.Failure(ErrorCode.ROUTE_NOT_FOUND);
        }
        var stored = repository.latest(query.stationId());
        Instant now = clock.instant();
        ArrivalSnapshot snapshot = stored.orElse(null);
        ArrivalRefresh refresh = snapshot == null || snapshot.refreshDueAt(now)
            ? gateway.request(query.stationId())
            : new ArrivalRefresh(ArrivalRefresh.Status.IDLE, null, snapshot.refreshAfterAt());
        now = clock.instant();
        Snapshot info = snapshot == null ? null : new Snapshot(snapshot.id(), snapshot.receivedAt(), null,
            snapshot.sourceQueryTime(), snapshot.refreshAfterAt(), snapshot.staleAt());
        Refresh update = refreshOf(refresh);
        if (snapshot == null || !snapshot.usableAt(now)) {
            return new StationArrivalsResponse(query.stationId(), now, "UNAVAILABLE",
                snapshot == null ? "NOT_COLLECTED" : "STALE", info, update, List.of());
        }
        List<Route> routes = new ArrayList<>();
        for (RouteArrival route : snapshot.routes()) {
            Visit visit = visits.stream().filter(v -> v.sourceRouteId().equals(route.routeId())
                && v.stopSequence() == route.sourceStopOrder() && !snapshot.receivedAt().isBefore(v.validFrom()))
                .findFirst().orElse(null);
            String routeId = visit == null ? route.routeId() : visit.routeId();
            if (query.routeId() != null && !query.routeId().equals(routeId)) continue;
            if (query.stopSequence() != null && (visit == null || visit.stopSequence() != query.stopSequence())) continue;
            routes.add(routeOf(route, visit, snapshot, now));
        }
        String status = routes.stream().anyMatch(route -> route.status().equals("READY")) ? "READY"
            : routes.stream().anyMatch(route -> route.status().equals("UNAVAILABLE")) ? "UNAVAILABLE"
            : routes.isEmpty() && !snapshot.routes().isEmpty() ? "UNAVAILABLE" : "NO_PREDICTION";
        String reason = status.equals("UNAVAILABLE") ? (routes.isEmpty() ? "NO_VERIFIED_ROUTE_MATCH" : "PREDICTION_ELAPSED") : null;
        return new StationArrivalsResponse(query.stationId(), now, status, reason, info, update, routes);
    }

    private static Route routeOf(RouteArrival route, Visit visit, ArrivalSnapshot snapshot, Instant now) {
        List<Vehicle> vehicles = route.arrivals().stream().map(value -> vehicleOf(value, snapshot, now)).toList();
        String status = vehicles.isEmpty() ? "NO_PREDICTION"
            : vehicles.stream().anyMatch(vehicle -> vehicle.eta().status().equals("AVAILABLE")) ? "READY" : "UNAVAILABLE";
        TargetVisit target = new TargetVisit(visit == null ? null : Long.toString(visit.referenceVersionId()),
            visit == null ? null : visit.stopSequence(), route.sourceStopOrder(),
            visit == null ? "UNVERIFIED" : "MATCHED", visit == null ? null : visit.direction());
        return new Route(visit == null ? route.routeId() : visit.routeId(), route.name(), target, status,
            status.equals("UNAVAILABLE") ? "PREDICTION_ELAPSED" : null, vehicles);
    }

    private static Vehicle vehicleOf(Prediction value, ArrivalSnapshot snapshot, Instant now) {
        Instant expected = value.expectedAt(snapshot.receivedAt());
        boolean usable = !expected.isBefore(now);
        Eta eta = new Eta(usable ? "AVAILABLE" : "UNAVAILABLE", usable ? null : "PREDICTION_ELAPSED",
            value.resolution(), snapshot.receivedAt(), "RECEIVED_AT_APPROXIMATION", expected,
            usable ? Math.max(0, (Duration.between(now, expected).toMillis() + 999) / 1000) : null);
        return new Vehicle(value.vehicleId(), value.ordinal(), value.remainingStops(), eta,
            new Seat(value.remainingSeats() == null ? "UNKNOWN" : "EXACT", value.remainingSeats(), value.seatUnknownReason()),
            value.congestion(), value.vehicleType(), value.taglessSupported());
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
}
