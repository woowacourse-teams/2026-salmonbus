package com.gustler.backend.routecatalog.domain;

/** GBIS가 제공한 X/Y 원자료. 거리 임계값이나 실제 이전 여부는 여기서 판단하지 않는다. */
public record StopCoordinates(double x, double y) {
    public StopCoordinates {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException("좌표는 유한한 값이어야 한다");
        }
    }

    public static StopCoordinates ofNullable(Double x, Double y) {
        if (x == null || y == null) {
            return null;
        }
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            return null;
        }
        return new StopCoordinates(x, y);
    }
}
