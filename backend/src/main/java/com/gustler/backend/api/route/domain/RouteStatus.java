package com.gustler.backend.api.route.domain;

public enum RouteStatus {

    FORECAST_READY,
    PREPARING,
    ;

    public static RouteStatus from(boolean activeModelExists) {
        if (activeModelExists) {
            return FORECAST_READY;
        }
        return PREPARING;
    }
}
