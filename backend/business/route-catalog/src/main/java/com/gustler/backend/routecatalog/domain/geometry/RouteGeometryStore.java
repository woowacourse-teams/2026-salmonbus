package com.gustler.backend.routecatalog.domain.geometry;

import java.util.Optional;

public interface RouteGeometryStore {
    Optional<Reference> current(String publicRouteId);
    Publication publish(Reference reference, RouteGeometrySource.Read read);

    record Reference(long routeId, String sourceRouteId, long versionId) { }
    enum Publication { UPDATED, UNCHANGED, REFERENCE_CHANGED }
}
