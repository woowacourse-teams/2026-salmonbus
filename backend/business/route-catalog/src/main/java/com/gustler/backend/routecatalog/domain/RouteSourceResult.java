package com.gustler.backend.routecatalog.domain;

public sealed interface RouteSourceResult {

    record Success(UpstreamRoute route) implements RouteSourceResult {
    }

    record Failed(String reason) implements RouteSourceResult {
    }
}
