package com.gustler.backend.api.route;

public class RouteNotFoundException extends RuntimeException {

    public RouteNotFoundException() {
        super("unknown routeId");
    }
}
