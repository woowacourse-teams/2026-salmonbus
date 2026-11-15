package com.gustler.backend.forecasting.application.pipeline;

/**
 * 관측 배치에 저장된 차량 관측 한 줄이다. {@code vehicle_observation}의 열을 거르지 않고 그대로 담는다.
 * 차량 번호와 잔여석, 좌석 모름 사유는 비어 있을 수 있다. 정수 열이 DB에서 비어 있으면 0으로 담는다({@code ResultSet.getInt}의 값이다).
 * 예보 입력으로 쓸지와 좌석 값을 어떻게 읽을지는 {@link ForecastStage}가 정한다.
 */
public record ForecastObservationRow(
    long observationId,
    String vehicleId,
    int passedStopOrder,
    int stopOrder,
    int runningState,
    Integer remainingSeats,
    String seatUnknownReason,
    int crowdLevel
) {
}
