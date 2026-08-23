package com.gustler.backend.api.route;

public class InvalidRouteIdException extends IllegalArgumentException {

    public InvalidRouteIdException() {
        super("routeId must be 9 digits");
    }
}
