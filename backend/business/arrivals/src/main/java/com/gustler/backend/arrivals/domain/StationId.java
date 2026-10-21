package com.gustler.backend.arrivals.domain;

/**
 * 공개 정류장 식별의 형식을 검증하는 값이다.
 * 노선 안의 방문 순번과 구분한다.
 */
public record StationId(String value) {
    public StationId {
        if (value == null || !value.matches("[0-9]{9}")) {
            throw new IllegalArgumentException("stationId는 9자리 숫자여야 한다");
        }
    }
}
