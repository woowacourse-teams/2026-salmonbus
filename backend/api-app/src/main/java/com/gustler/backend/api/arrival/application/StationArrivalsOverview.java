package com.gustler.backend.api.arrival.application;

import com.gustler.backend.api.arrival.domain.ArrivalAvailability;
import com.gustler.backend.api.arrival.domain.RouteArrivals;
import com.gustler.backend.arrival.contract.ArrivalRefresh;
import com.gustler.backend.arrival.contract.ArrivalSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 정류장 도착정보 조회 결과다. HTTP 응답 형식과 캐시 헤더로 변환하는 일은 컨트롤러가 담당한다.
 */
public record StationArrivalsOverview(String stationId, Instant generatedAt, ArrivalAvailability status,
    String reason, ArrivalSnapshot snapshot, ArrivalRefresh refresh,
    List<RouteArrivals> routes, Duration cacheMaxAge) {
    public StationArrivalsOverview {
        routes = List.copyOf(routes);
    }
}
