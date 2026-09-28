package com.gustler.backend.observations.domain;

/** 노선의 차량 관측을 조회한다. 응답은 결과를 저장할 때 수집 업무의 값으로 해석한다. */
public interface ObservationSource {
    ObservationReply read(String sourceRouteId, String keyAlias);
}
