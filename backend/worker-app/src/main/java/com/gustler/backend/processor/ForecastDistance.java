package com.gustler.backend.processor;

public record ForecastDistance(
    int stopCount
) {

    private static final int MINIMUM_STOP_COUNT = 1;
    private static final int MAXIMUM_STOP_COUNT = 12;

    public ForecastDistance {
        if (!covers(stopCount)) {
            throw new IllegalArgumentException(
                "예보는 %d정류장 앞부터 %d정류장 앞까지만 낸다: %d"
                    .formatted(MINIMUM_STOP_COUNT, MAXIMUM_STOP_COUNT, stopCount)
            );
        }
    }

    /** 예보를 내는 범위 안인가. 대상을 고르는 쪽이 예외를 안 쓰고 물어볼 수 있어야 한다. */
    public static boolean covers(
        final int stopCount
    ) {
        return MINIMUM_STOP_COUNT <= stopCount && stopCount <= MAXIMUM_STOP_COUNT;
    }
}
