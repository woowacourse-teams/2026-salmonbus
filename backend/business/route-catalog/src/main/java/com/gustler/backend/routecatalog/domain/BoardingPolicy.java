package com.gustler.backend.routecatalog.domain;

/**
 * 원천 정류장 ID의 접두어를 기준으로 승차 허용 여부를 판정한다.
 */
public final class BoardingPolicy {
    private static final String TRANSIT_ONLY_STOP_ID_PREFIX = "277";

    private BoardingPolicy() {
    }

    public static boolean allowsBoardingAt(
        String stopId
    ) {
        return !stopId.startsWith(TRANSIT_ONLY_STOP_ID_PREFIX);
    }
}
