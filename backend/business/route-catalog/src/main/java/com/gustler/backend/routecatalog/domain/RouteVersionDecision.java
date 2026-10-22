package com.gustler.backend.routecatalog.domain;

/**
 * 새 수신 자료가 정류장 판본 교체·시간표 수정·현 상태 유지 중 무엇을 요구하는지 나타낸다.
 */
public enum RouteVersionDecision {
    OPEN_NEW_VERSION,
    REVISE_TIMETABLE,
    KEEP_CURRENT_VERSION,
    ;
}
