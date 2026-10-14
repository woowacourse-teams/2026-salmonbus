package com.gustler.backend.api.arrival.domain;

import com.gustler.backend.arrival.contract.ArrivalSnapshot.RouteArrival;
import java.time.Instant;
import java.util.List;

public record RouteArrivals(String routeId, String name, ArrivalVisit targetVisit,
                            int sourceStopOrder, List<ArrivalPrediction> arrivals) {
    public RouteArrivals {
        arrivals = List.copyOf(arrivals);
    }

    public static RouteArrivals at(RouteArrival source, ArrivalVisit visit, Instant receivedAt, Instant now) {
        return new RouteArrivals(visit == null ? source.routeId() : visit.routeId(), source.name(), visit,
            source.sourceStopOrder(), source.arrivals().stream()
                .map(prediction -> ArrivalPrediction.at(prediction, receivedAt, now)).toList());
    }

    public ArrivalAvailability status() {
        if (arrivals.isEmpty()) return ArrivalAvailability.NO_PREDICTION;
        return arrivals.stream().anyMatch(ArrivalPrediction::available)
            ? ArrivalAvailability.READY : ArrivalAvailability.UNAVAILABLE;
    }

    public String reason() {
        return status() == ArrivalAvailability.UNAVAILABLE ? "PREDICTION_ELAPSED" : null;
    }
}
