package com.gustler.backend.observations.domain;

/**
 * 원천 관측을 읽어 지연 해석 가능한 수신 결과로 제공한다.
 * HTTP 원문 해석과 저장 업무를 분리한다.
 */
public interface ObservationSource {
    /**
     * 지정 원천 노선을 선택한 키로 호출한다. 정상 응답과 키·전송 실패를 구별하는 결과를 반환한다.
     */
    ObservationReply read(String sourceRouteId, String keyAlias);
}
