package com.gustler.backend.collector;

/**
 * 상류 응답의 차량 한 줄. 응답에서 몇 번째 줄이었는지와 그 줄을 정규화한 관측을 같이 들고 있다.
 * 뜻을 모르는 운행 상태의 차량을 빼고 나면 줄 번호가 비므로, 번호를 관측에 붙여 둬야 한다.
 */
public record UpstreamObservationRow(
    int sourceRowNumber,
    VehicleObservation observation
) {
}
