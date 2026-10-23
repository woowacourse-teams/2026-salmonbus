package com.gustler.backend.observations.domain;

/**
 * 상류 응답의 차량 한 줄. 응답에서 몇 번째 줄이었는지와 그 줄을 정규화한 관측을 함께 보관한다.
 * 해석할 수 없는 운행 상태의 차량을 제외하면 줄 번호가 비므로 번호를 관측에 붙여 둬야 한다.
 */
public record UpstreamObservationRow(
    int sourceRowNumber,
    VehicleObservation observation
) {
}
