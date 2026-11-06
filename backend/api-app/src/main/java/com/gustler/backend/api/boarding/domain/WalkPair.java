package com.gustler.backend.api.boarding.domain;

/**
 * 정적 묶음에 저장한 정류장 쌍 도보 거리와 그 거리를 계산할 때 쓴 두 좌표.
 */
public record WalkPair(
    String stationA,
    String stationB,
    double ax,
    double ay,
    double bx,
    double by,
    int walkMeters
) {
    public String other(String stationId) {
        return stationA.equals(stationId) ? stationB : stationA;
    }

    public double[] locationOf(String stationId) {
        return stationA.equals(stationId) ? new double[] {ax, ay} : new double[] {bx, by};
    }
}
