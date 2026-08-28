package com.gustler.backend.processor;

import java.time.Instant;

/**
 * 아직 라벨을 못 채운 예보 한 줄. 회수에 필요한 것만 든다.
 *
 * <p>예보를 낸 시점의 통과 순번은 대상 순번에서 지평을 뺀 값이라 따로 안 읽는다.
 */
public record PendingForecast(
    long vehicleObservationId,
    int targetStopOrder,
    long routeVersionId,
    String vehicleId,
    int stopsToTarget,
    Instant observedAt
) {

    public PendingForecast {
        if (!ForecastDistance.covers(stopsToTarget)) {
            throw new IllegalArgumentException("예보는 1정류장 앞부터 12정류장 앞까지만 낸다: " + stopsToTarget);
        }
        if (observedAt == null) {
            throw new IllegalArgumentException("예보를 낸 관측에는 시각이 있어야 한다");
        }
    }

    /** 예보를 낼 때 그 차량이 지나온 정류장의 순번. */
    public int passedStopOrder() {
        return targetStopOrder - stopsToTarget;
    }

    /** 차량 아이디가 없으면 도착 관측을 찾을 길이 없다. */
    public boolean hasVehicleId() {
        return vehicleId != null;
    }
}
