package com.gustler.backend.api.route;

public class InvalidRouteIdException extends IllegalArgumentException {

    public InvalidRouteIdException() {
        super("routeId는 9자리 숫자여야 합니다.");
    }
}
