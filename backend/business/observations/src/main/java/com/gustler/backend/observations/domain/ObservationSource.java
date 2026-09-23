package com.gustler.backend.observations.domain;

/** 노선의 차량 관측을 조회하고 수집 업무의 값으로 반환한다. */
public interface ObservationSource {
    ObservationResponse read(String sourceRouteId);
}
