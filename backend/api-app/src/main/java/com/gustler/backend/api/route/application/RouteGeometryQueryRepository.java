package com.gustler.backend.api.route.application;

import com.gustler.backend.api.route.domain.RouteGeometry;
import java.util.List;
import java.util.Optional;

public interface RouteGeometryQueryRepository {
    Optional<Reference> reference(String routeId);
    List<RouteGeometry.Stop> stops(long versionId);
    Optional<RouteGeometry.Shape> shape(long versionId);

    record Reference(String routeId, long versionId, String name, String startPoint, String endPoint, Integer turnSequence) { }
}
