package com.gustler.backend.api.arrival.domain;

import com.gustler.backend.arrival.contract.ArrivalSnapshot.RouteArrival;
import java.time.Instant;
import java.util.List;

/**
 * 한 노선의 원천 예측과 확인한 대상 방문을 수신 시각 기준으로 연결한 값이다.
 */
public record RouteArrivals(String routeId, String name, ArrivalVisit targetVisit,
    int sourceStopOrder, List<ArrivalPrediction> arrivals) {
    public RouteArrivals {
        arrivals = List.copyOf(arrivals);
    }

    public static RouteArrivals at(
        RouteArrival source,
        ArrivalVisit visit,
        Instant receivedAt,
        Instant now
    ) {
        String routeId = source.routeId();
        if (visit != null) {
            routeId = visit.routeId();
        }
        return new RouteArrivals(routeId, source.name(), visit,
            source.sourceStopOrder(), source.arrivals()
                .stream()
                .map(prediction -> ArrivalPrediction.at(prediction, receivedAt, now))
                .toList());
    }

    public ArrivalAvailability status() {
        if (arrivals.isEmpty()) {
            return ArrivalAvailability.NO_PREDICTION;
        }
        if (arrivals.stream().anyMatch(ArrivalPrediction::available)) {
            return ArrivalAvailability.READY;
        }
        return ArrivalAvailability.UNAVAILABLE;
    }

    public String reason() {
        if (status() == ArrivalAvailability.UNAVAILABLE) {
            return "PREDICTION_ELAPSED";
        }
        return null;
    }
}
