package com.gustler.backend.observations.domain;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * 수신 결과를 저장 기준 시각에 해석하는 경계다.
 * 원천 행 참조는 진단에 필요한 범위에서만 제공한다.
 */
public interface ObservationReply {
    /**
     * 원천 결과를 성공·거부·불확실한 실패로 구별해 해석한다. 관측되지 않은 좌석을 0으로 대체하지 않는다.
     */
    ObservationResponse interpret(OffsetDateTime receivedAt);

    /** 정규화 전 응답의 공개 노선/정류장 필드만 반환한다. 추가 호출이나 차량 식별자 기록은 하지 않는다. */
    default Optional<StopReference> sourceReference(final int stopOrder, String stopId) {
        return Optional.empty();
    }

    record StopReference(int sourceRowNumber, String routeId, Integer stopOrder, String stopId) {
    }
}
