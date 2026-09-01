package com.gustler.backend.processor;

import java.time.Instant;

/**
 * 관측 한 건에서 예보에 그대로 넘기는 값.
 *
 * <p>혼잡도는 상류가 1등급에서 4등급으로 주고 안 줄 때도 있다. 적재가 0을 미제공으로 접어
 * 두어서 여기 오는 값은 1에서 4 사이거나 비어 있다.
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
        if (crowdLevel != null && (crowdLevel < LOWEST_CROWD_LEVEL || crowdLevel > HIGHEST_CROWD_LEVEL)) {
            throw new IllegalArgumentException(
                "혼잡도는 %d등급부터 %d등급까지다: %d".formatted(LOWEST_CROWD_LEVEL, HIGHEST_CROWD_LEVEL, crowdLevel)
            );
        }
    }

    public boolean hasKnownSeats() {
        return remainingSeats != null && remainingSeats >= 0;
    }

    public boolean hasKnownCrowdLevel() {
        return crowdLevel != null;
    }
}
