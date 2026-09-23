package com.gustler.backend.routecatalog.domain;

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
