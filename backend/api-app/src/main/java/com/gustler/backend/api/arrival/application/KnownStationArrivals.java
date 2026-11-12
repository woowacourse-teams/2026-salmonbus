package com.gustler.backend.api.arrival.application;

import com.gustler.backend.api.arrival.domain.ArrivalVisit;
import java.util.List;

/**
 * 입구 확인을 마친 정류장의 도착정보 조회 결과와, 그 결과의 노선을 고를 때 쓴 우리 노선 방문이다.
 */
public record KnownStationArrivals(StationArrivalsOverview overview, List<ArrivalVisit> visits) {
    public KnownStationArrivals {
        visits = List.copyOf(visits);
    }
}
