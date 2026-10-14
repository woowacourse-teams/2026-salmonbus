package com.gustler.backend.routecatalog.domain.geometry;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface RouteGeometrySource {
    Optional<Read> read(String sourceRouteId);
    record Read(RouteShape shape, OffsetDateTime receivedAt) { }
}
