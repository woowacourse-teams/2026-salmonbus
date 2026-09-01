package com.gustler.backend.processor;

import java.time.Instant;

/**
 * 아직 라벨을 못 채운 예보 한 줄. 회수에 필요한 것만 든다.
 *
 * <p>예보를 낼 때 그 차량이 지나온 순번은 대상 순번에서 지평을 뺀 값이라 따로 안 읽는다.
 *
 * <p>시각을 둘 든다. {@code observedAt} 은 관측 시각이라 연속 관측 간격을 재는 기준이고,
 * {@code generatedAt} 은 예보를 계산한 시각이라 <b>도착 후보를 어디서부터 볼지의 하한</b>이다.
 * 둘을 가르는 이유는 예보가 관측보다 늦게 나올 수 있기 때문이다. 그 사이에 이미 DB 에 들어와 있던
 * 도착 관측을 라벨로 쓰면 예보를 내기 전에 답을 본 것이 된다.
 */
public record PendingForecast(
    long vehicleObservationId,
    int targetStopOrder,
    long routeVersionId,
    String vehicleId,
    int stopsToTarget,
    Instant observedAt,
    Instant generatedAt
) {

    public PendingForecast {
        if (!ForecastDistance.covers(stopsToTarget)) {
            throw new IllegalArgumentException("예보는 1정류장 앞부터 12정류장 앞까지만 낸다: " + stopsToTarget);
        }
        if (observedAt == null || generatedAt == null) {
            throw new IllegalArgumentException("예보에는 관측 시각과 계산 시각이 둘 다 있어야 한다");
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
