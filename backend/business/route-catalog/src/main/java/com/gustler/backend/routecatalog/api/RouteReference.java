package com.gustler.backend.routecatalog.api;

/**
 * 다른 업무가 현재 노선 판본을 참조할 때 사용하는 식별 값이다.
 */
public record RouteReference(long routeVersionId) {
    public RouteReference {
        if (routeVersionId < 1) {
            throw new IllegalArgumentException("노선 버전 ID는 양수여야 한다");
        }
    }
}
