package com.gustler.backend.api.salmon.application;

/**
 * 하차 정류장 규칙이 이미 고른 재탑승 한 건.
 * 이 타입은 정류장을 고르지 않고, 고른 결과의 정류장과 시간만 담는다.
 */
public record AlightingLeg(
    String oppositeStationId,
    int oppositeSequence,
    int walkToOppositeSeconds,
    String alightStationId,
    int alightSequence,
    int rideSeconds,
    String reboardStationId,
    int reboardSequence,
    int walkAfterSeconds
) {
    public AlightingLeg {
        if (isBlank(oppositeStationId) || isBlank(alightStationId) || isBlank(reboardStationId)) {
            throw new IllegalArgumentException("stationId must not be blank");
        }
        if (oppositeSequence < 1 || alightSequence < 1 || reboardSequence < 1
            || walkToOppositeSeconds < 0 || rideSeconds < 0 || walkAfterSeconds < 0) {
            throw new IllegalArgumentException(
                "stop sequences must be positive and durations must not be negative");
        }
    }

    private static boolean isBlank(String stationId) {
        return stationId == null || stationId.isBlank();
    }
}
