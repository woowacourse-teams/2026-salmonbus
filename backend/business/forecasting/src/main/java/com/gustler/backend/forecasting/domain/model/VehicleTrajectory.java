package com.gustler.backend.forecasting.domain.model;

/**
 * 차량 한 대의 관측과 궤적 입력.
 *
 * <p>잔여석은 두 필드에 있다. {@code observation} 은 예보에 그대로 전달할 값이고,
 * {@code seats} 는 값을 모를 때의 사유도 포함한다. 적재 시 사유를 따로 저장하므로
 * 조회 시 잔여석 하나로 합치면 사유가 사라진다.
 *
 * <p>관측 행 번호도 함께 보관한다. 예보 행이 해당 관측을 참조하며 행 번호는 seat_forecast 기본키의
 * 일부이므로, 이 값이 없으면 예보를 어느 관측의 결과로 저장할지 정할 수 없다.
 *
 * <p>{@code maximumSeatsEverObserved} 만 관측 범위가 다르다. 좌석 기울기 · 앞차 ·
 * 연속 만석은 궤적을 잇는 30분 창 안에서 계산하지만 이 값은 해당 차량이 해당 노선 버전에서
 * 남긴 전체 관측에서 계산한다. 셀 통계 집계와 범위가 같아야 하며, 창으로 제한하면
 * 집계와 서빙이 서로 다른 수를 분모로 사용하게 된다.
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
