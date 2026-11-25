package com.gustler.backend.observations.domain;

/**
 * GBIS 응답의 차량 한 행과 정규화한 관측을 보관한다.
 * 저장 대상에서 제외한 차량이 있어도 원본 응답의 행 번호를 유지한다.
 */
public record VehicleObservationRow(
    int sourceRowNumber,
    VehicleObservation observation
) {

}
