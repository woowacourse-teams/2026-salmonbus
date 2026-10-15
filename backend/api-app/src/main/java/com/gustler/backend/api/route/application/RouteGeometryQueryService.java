package com.gustler.backend.api.route.application;

import com.gustler.backend.api.route.RouteGeometryReferenceConflictException;
import com.gustler.backend.api.route.InvalidReferenceVersionException;
import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.route.RouteNotFoundException;
import com.gustler.backend.api.route.domain.RouteGeometry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class RouteGeometryQueryService {
    private final RouteGeometryQueryRepository repository;

    public RouteGeometryQueryService(RouteGeometryQueryRepository repository) { this.repository = repository; }

    public RouteGeometry get(RouteId routeId, Long requestedVersion) {
        if (requestedVersion != null && requestedVersion < 1) throw new InvalidReferenceVersionException();
        var reference = repository.reference(routeId.value()).orElseThrow(RouteNotFoundException::new);
        if (requestedVersion != null && requestedVersion != reference.versionId()) throw new RouteGeometryReferenceConflictException();
        return new RouteGeometry(reference.routeId(), reference.versionId(), reference.name(), reference.startPoint(),
            reference.endPoint(), reference.turnSequence(), repository.stops(reference.versionId()),
            repository.shape(reference.versionId()).orElse(null));
    }
}
