package com.gustler.backend.routecatalog.domain;

/**
 * 노선 판본 안의 정류장 방문 방향이다.
 * 같은 정류장의 서로 다른 방문을 합치지 않는다.
 */
public enum StopDirection {
    UP,
    DOWN,
    ;
}
