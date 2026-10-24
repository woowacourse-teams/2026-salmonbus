package com.gustler.backend.api.board.application;

/**
 * 탑승판에 접근 차량으로 올릴 수 있는 관측 한 건이다.
 *
 * <p>sourceRowNumber는 선택된 관측 묶음 안에서 유일하다. 차량 ID가 없어도 예보를 연결할 수 있다.
 * stopOrder는 버스가 있는 정류장 순번(GBIS stationSeq)이고, passedStopOrder는 버스가 지난 정류장 순번이다.
 * 정류장에 서 있는 차량만 passedStopOrder가 stopOrder보다 하나 작다.
 */
public record BoardVehicleObservation(
    String vehicleId,
    int sourceRowNumber,
    int passedStopOrder,
    int stopOrder
) {
}
