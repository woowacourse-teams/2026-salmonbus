package com.gustler.backend.forecasting.domain.model;

/**
 * 예보 대상까지의 정류장 방문 거리를 나타낸다.
 * 거리의 단위는 미터가 아니라 정류장 수다.
 */
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

    /** 예보 대상 거리가 지원 범위에 속하는지 예외 없이 확인한다. */
    public static boolean covers(
        final int stopCount
    ) {
        return MINIMUM_STOP_COUNT <= stopCount && stopCount <= MAXIMUM_STOP_COUNT;
    }
}
