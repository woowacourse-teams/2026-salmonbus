package com.gustler.backend.api.board.domain;

/**
 * 노선 방문의 상행·하행 구분이다.
 * 동일 정류장 ID의 반복 방문도 방향별로 구분할 수 있다.
 */
public enum BoardDirection {
    UP,
    DOWN,
    ;
}
