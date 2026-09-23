package com.gustler.backend.routecatalog.api;

public record RouteReference(long routeVersionId) {

    public RouteReference {
        if (routeVersionId < 1) {
            throw new IllegalArgumentException("노선 버전 ID는 양수여야 한다");
        }
    }
}
