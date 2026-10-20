package com.gustler.backend.routecatalog.domain;

/**
 * 필수 노선을 잠가 읽고 도메인이 결정한 판본 변경을 저장한다.
 * 기존 필수 조회의 실패 계약을 유지한다.
 */
public interface RouteRepository {
    /**
     * 존재해야 하는 노선을 잠가 읽는다. 잠금 수명은 호출 트랜잭션에 따르며 노선 부재는 필수 조회 실패로 처리한다.
     */
    Route findByIdForUpdate(long routeId);

    /**
     * 도메인이 결정한 판본 유효기간·시간표·정류장 변경을 저장하고 현재 노선 판본의 식별자를 반환한다.
     */
    long save(Route route);
}
