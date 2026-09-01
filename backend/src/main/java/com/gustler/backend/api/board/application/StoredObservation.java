package com.gustler.backend.api.board.application;

/**
 * 그 묶음의 관측 한 줄.
 *
 * <p>차량을 가리키는 열쇠는 {@code vehicleId} 가 아니라 {@code sourceRowNumber} 다. 상류가 차량
 * 식별자를 안 줄 수 있어 {@code vehicleId} 는 비어 있을 수 있고, {@code sourceRowNumber} 는
 * 묶음 안에서 유일하다.
 */
public record StoredObservation(
    int sourceRowNumber,
    String vehicleId,
    int passedStopOrder
) {
}
