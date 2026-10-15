package com.gustler.backend.arrivals.domain;

import com.gustler.backend.arrival.contract.ArrivalSnapshot.RouteArrival;
import java.time.Instant;
import java.time.Duration;
import java.util.List;

public interface ArrivalSource {
    Result fetch(String stationId, String keyAlias);

    sealed interface Result permits Success, Failed { }
    record Success(Instant receivedAt, String sourceQueryTime, List<RouteArrival> routes) implements Result {
        public Success { routes = List.copyOf(routes); }
    }
    record Failed(Reason reason, Duration retryAfter) implements Result {
        public Failed(Reason reason) { this(reason, null); }
    }
    enum Reason { TRANSPORT_ERROR, UPSTREAM_ERROR, UNREADABLE_RESPONSE, DAILY_QUOTA_EXCEEDED,
                  RATE_LIMITED, KEY_REJECTED }
}
