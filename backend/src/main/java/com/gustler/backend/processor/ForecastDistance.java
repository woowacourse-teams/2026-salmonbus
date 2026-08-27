package com.gustler.backend.processor;

public record ForecastDistance(
    int stopCount
) {

    private static final int MINIMUM_STOP_COUNT = 1;
    private static final int MAXIMUM_STOP_COUNT = 12;

    public ForecastDistance {
        if (!isForecastable(stopCount)) {
            throw new IllegalArgumentException(
                "예보는 %d정류장 앞부터 %d정류장 앞까지만 낸다: %d"
                    .formatted(MINIMUM_STOP_COUNT, MAXIMUM_STOP_COUNT, stopCount)
            );
        }
    }

    private static boolean isForecastable(
        final int stopCount
    ) {
        return MINIMUM_STOP_COUNT <= stopCount && stopCount <= MAXIMUM_STOP_COUNT;
    }
}
