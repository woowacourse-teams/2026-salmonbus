package com.gustler.backend.arrivals.domain;

public record StationId(String value) {
    public StationId {
        if (value == null || !value.matches("[0-9]{9}")) {
            throw new IllegalArgumentException("stationId는 9자리 숫자여야 한다");
        }
    }
}
