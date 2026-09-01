package com.gustler.backend.processor;

import java.time.Instant;

/**
 * 저장할 예보 한 줄. 한 관측이 한 정류장에 도착할 때의 좌석 예보다.
 *
 * <p>회수한 라벨은 담지 않는다. 갓 만든 예보는 아직 안 닫힌 상태로만 쓰이고,
 * 라벨은 나중에 회수 배치가 같은 행에 채운다.
 */
public record SeatForecast(
    long vehicleObservationId,
    long routeVersionId,
    int targetStopOrder,
    int stopsToTarget,
    long modelDeploymentId,
    int demandStatisticsRevision,
    double seatFullChanceRaw,
    double seatFullChance,
    Double expectedSeats,
    Instant generatedAt
) {

    public SeatForecast {
        if (!ForecastDistance.covers(stopsToTarget)) {
            throw new IllegalArgumentException("예보는 1정류장 앞부터 12정류장 앞까지만 낸다: " + stopsToTarget);
        }
        if (expectedSeats != null && !(expectedSeats >= 0)) {
            throw new IllegalArgumentException("기대 잔여석은 0석 이상이다: " + expectedSeats);
        }
        if (generatedAt == null) {
            throw new IllegalArgumentException("예보에는 계산 시각이 있어야 한다");
        }
    }

    /**
     * 낸 예보를 저장할 모양으로 옮긴다.
     *
     * <p>지평은 대상 순번에서 관측이 지나온 순번을 뺀 값이고 그 뺄셈은 대상이 이미 하고 있다.
     * 여기서 다시 세면 두 곳이 어긋날 자리가 생긴다.
     */
    public static SeatForecast of(
        final long vehicleObservationId,
        VehicleStopTarget target,
        SeatForecastResult result,
        final long modelDeploymentId,
        final int demandStatisticsRevision,
        Instant generatedAt
    ) {
        return new SeatForecast(
            vehicleObservationId,
            target.observation().routeVersionId(),
            target.stopOrder(),
            target.distance().stopCount(),
            modelDeploymentId,
            demandStatisticsRevision,
            result.fullChanceRaw(),
            result.fullChance(),
            result.distribution().expectedSeats(),
            generatedAt);
    }
}
