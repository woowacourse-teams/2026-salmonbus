package com.gustler.backend.forecasting.domain.model;

/**
 * 차량 한 대의 관측과 그에 딸린 궤적 재료들.
 *
 * <p>잔여석이 두 자리에 있다. {@code observation} 이 든 것은 예보에 그대로 넘길 값이고,
 * {@code seats} 는 모를 때 왜 모르는지까지 든다. 적재가 사유를 따로 남겨 뒀는데
 * 읽으면서 잔여석 하나로 합치면 그 사유가 사라진다.
 *
 * <p>관측 행 번호를 같이 든다. 예보 행이 그 관측에 매달리고 행 번호가 seat_forecast 기본키의
 * 일부라, 이 값이 없으면 낸 예보를 어느 관측에서 나온 것으로 저장할지 정할 수 없다.
 *
 * <p><b>{@code maximumSeatsEverObserved} 만 보는 범위가 다르다.</b> 좌석 기울기 · 앞차 ·
 * 연속 만석 셋은 궤적을 잇는 30분 창 안에서 나오는데, 이 값은 그 차량이 그 노선 버전에서
 * 남긴 관측 <b>전부</b>에서 나온다. 셀 통계 집계가 쓰는 범위와 같아야 해서다. 창으로 자르면
 * 집계 쪽과 서빙 쪽이 서로 다른 수로 나눈 값을 만든다.
 */
public record VehicleTrajectory(
    long vehicleObservationId,
    ObservedVehicle observation,
    ObservedSeats seats,
    SeatSlope seatSlope,
    PrecedingVehicle precedingVehicle,
    FullSeatStreak fullSeatStreak,
    int maximumSeatsEverObserved
) {

    /**
     * 최대 관측 잔여석의 허용 최솟값.
     *
     * <p>설계행렬에서 분모로 사용하므로 0을 허용하지 않는다.
     * 이 값은 알 수 없는 정원을 1석으로 대체하는 기본값이 아니다.
     * 최대 관측 잔여석이 없는 차량은 궤적 구성 단계에서 제외한다.
     */
    static final int SMALLEST_MAXIMUM_SEATS = 1;

    public VehicleTrajectory {
        if (maximumSeatsEverObserved < SMALLEST_MAXIMUM_SEATS) {
            throw new IllegalArgumentException(
                "그 차량이 보여 준 최대 잔여석은 %d석 이상이다: %d"
                    .formatted(SMALLEST_MAXIMUM_SEATS, maximumSeatsEverObserved)
            );
        }
    }
}
