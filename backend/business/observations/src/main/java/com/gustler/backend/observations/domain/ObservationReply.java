package com.gustler.backend.observations.domain;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface ObservationReply {
    ObservationResponse interpret(OffsetDateTime receivedAt);

    /** 정규화 전 응답의 공개 노선/정류장 필드만 반환한다. 추가 호출이나 차량 식별자 기록은 하지 않는다. */
    default Optional<StopReference> sourceReference(int stopOrder, String stopId) {
        return Optional.empty();
    }

    record StopReference(int sourceRowNumber, String routeId, Integer stopOrder, String stopId) { }
}
