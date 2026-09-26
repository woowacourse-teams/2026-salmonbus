package com.gustler.backend.forecasting.domain.evaluation;

import com.gustler.backend.forecasting.domain.model.ForecastDistance;

import java.time.Instant;

/**
 * 아직 평가 결과가 없는 예보. 평가에 필요한 정보만 담는다.
 *
 * <p>예보 시점에 차량이 지나온 순번은 대상 순번에서 예보 거리를 뺀 값이므로 별도로 읽지 않는다.
 *
 * <p>두 시각을 보관한다. {@code observedAt}은 관측 시각으로, 연속 관측 간격을 재는 기준이다.
 * {@code generatedAt} 은 예보를 계산한 시각이라 <b>도착 후보를 어디서부터 볼지의 하한</b>이다.
 * 예보가 관측보다 늦게 나올 수 있으므로 두 시각을 구분한다. 그 사이에 이미 DB에 들어온
 * 도착 관측을 라벨로 쓰면 예보를 내기 전에 답을 본 것이 된다.
 */
public record PendingForecast(
    long vehicleObservationId,
    int targetStopOrder,
    long routeVersionId,
    String vehicleId,
    int stopsToTarget,
    Instant observedAt,
    Instant generatedAt,
    Long qualityDirection
) {

    public PendingForecast(long observationId, int target, long version, String vehicle,
                           int distance, Instant observedAt, Instant generatedAt) {
        this(observationId, target, version, vehicle, distance, observedAt, generatedAt, null);
    }

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
