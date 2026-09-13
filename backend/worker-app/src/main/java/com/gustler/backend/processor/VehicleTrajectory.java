package com.gustler.backend.processor;

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
 * 연속 만석 셋은 궤적을 잇는 30분 창 안에서 나오는데, 이 값은 그 차량이 그 노선 판본에서
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
     * 보여 준 최대 잔여석이 될 수 있는 가장 작은 값.
     *
     * <p>줄곧 만석이던 차량과 잔여석을 한 번도 안 보여 준 차량이 여기로 온다. 설계행렬이 이 값으로
     * 나누기 때문에 0이면 안 된다. 셀 통계 집계의 GREATEST(MAX(...), 1) 과 같은 바닥이다.
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
