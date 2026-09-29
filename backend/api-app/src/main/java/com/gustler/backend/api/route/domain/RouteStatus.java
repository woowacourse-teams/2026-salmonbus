package com.gustler.backend.api.route.domain;

public enum RouteStatus {

    FORECAST_READY,
    PREPARING,
    ;

    public static RouteStatus from(
        final boolean activeModelExists,
        final boolean forecastPublished
    ) {
        if (activeModelExists && forecastPublished) {
            return FORECAST_READY;
        }
        return PREPARING;
    }
}
