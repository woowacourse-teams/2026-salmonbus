package com.gustler.backend.observations.domain;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * GBIS 조회 결과를 저장 기준 시각에 맞춰 해석한다.
 * 진단에 필요한 응답 행 정보만 제공한다.
 */
public interface ObservationResponse {

    /** 조회 결과를 성공, GBIS 오류 응답, 결과를 확인할 수 없는 실패로 구분한다. 좌석을 관측하지 못했다면 0으로 대체하지 않는다. */
    CollectionResult interpret(OffsetDateTime receivedAt);

    /** 정규화 전 응답의 공개 노선/정류장 필드만 반환한다. 추가 호출이나 차량 식별자 기록은 하지 않는다. */
    default Optional<SourceStopReference> sourceReference(final int stopOrder, String stopId) {
        return Optional.empty();
    }

    record SourceStopReference(int sourceRowNumber, String routeId, Integer stopOrder, String stopId) {

    }
}
