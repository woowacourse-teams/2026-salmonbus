package com.gustler.backend.api.route;

public class RouteNotFoundException extends RuntimeException {

    public RouteNotFoundException() {
        super("등록되지 않은 노선입니다.");
    }
}
