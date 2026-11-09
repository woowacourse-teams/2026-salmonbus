package com.gustler.backend.api.boarding.application;

/**
 * 이동 방법 계산 조건. 시간에 따라 달라지는 방식이 생기면 출발 시각 같은 조건을 여기에 더한다.
 *
 * @param maxSeconds 출발부터 탑승 정류장 도착까지 허용하는 전체 시간(초)
 */
public record AccessConstraints(int maxSeconds) {
    public AccessConstraints {
        if (maxSeconds < 0) {
            throw new IllegalArgumentException("maxSeconds는 0 이상이어야 한다");
        }
    }
}
