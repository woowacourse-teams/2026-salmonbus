package com.gustler.backend.forecasting.domain.model;

import java.time.Instant;

/**
 * 관측 한 건에서 예보에 그대로 전달하는 값.
 *
 * <p>상류는 혼잡도를 1등급에서 4등급으로 제공하거나 생략한다. 적재 단계에서 0을 미제공으로
 * 처리하므로 이 값은 1에서 4 사이거나 비어 있다.
 */
public record ObservedVehicle(
    String vehicleId,
    long routeVersionId,
    int passedStopOrder,
    Instant observedAt,
    Integer remainingSeats,
    Integer crowdLevel
) {
    public static final int LOWEST_CROWD_LEVEL = 1;
    public static final int HIGHEST_CROWD_LEVEL = 4;

    public ObservedVehicle {
        if (crowdLevel != null && isOutsideCrowdLevelRange(crowdLevel)) {
            throw new IllegalArgumentException(
                "혼잡도는 %d등급부터 %d등급까지다: %d".formatted(LOWEST_CROWD_LEVEL, HIGHEST_CROWD_LEVEL, crowdLevel)
            );
        }
    }

    private static boolean isOutsideCrowdLevelRange(int crowdLevel) {
        return crowdLevel < LOWEST_CROWD_LEVEL || crowdLevel > HIGHEST_CROWD_LEVEL;
    }

    public boolean hasKnownSeats() {
        return remainingSeats != null && remainingSeats >= 0;
    }

    public boolean hasKnownCrowdLevel() {
        return crowdLevel != null;
    }
}
