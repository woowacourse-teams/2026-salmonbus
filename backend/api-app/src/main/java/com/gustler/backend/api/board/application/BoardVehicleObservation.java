package com.gustler.backend.api.board.application;

/** sourceRowNumber는 선택된 관측 묶음 안에서 유일하다. 차량 ID가 없어도 예보를 연결할 수 있다. */
public record BoardVehicleObservation(
    String vehicleId,
    int sourceRowNumber,
    int passedStopOrder
) {
}
