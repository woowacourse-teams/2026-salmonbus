package com.gustler.backend.routecatalog.domain;

public interface RouteRepository {

    Route findByIdForUpdate(long routeId);

    long save(Route route);
}
