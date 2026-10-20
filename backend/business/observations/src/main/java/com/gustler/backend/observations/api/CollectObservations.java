package com.gustler.backend.observations.api;

/**
 * 한 공개 노선의 관측 수집을 한 번 실행하는 업무 경계다.
 * 전송 예산과 결과 저장은 수집 흐름에서 조율한다.
 */
public interface CollectObservations {
    /**
     * 한 원천 노선의 판본 확인부터 할당량 예약·수신·저장까지 한 번 조율한다. 외부 호출 전체를 DB 트랜잭션으로 감싸지 않는다.
     */
    void collectOnce(String sourceRouteId);
}
