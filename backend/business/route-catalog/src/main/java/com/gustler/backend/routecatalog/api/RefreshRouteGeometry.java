package com.gustler.backend.routecatalog.api;

import java.time.OffsetDateTime;

public interface RefreshRouteGeometry {
    Outcome refresh(String routeId, OffsetDateTime requestedAt);

    enum Outcome { UPDATED, UNCHANGED, NO_REFERENCE, NO_QUOTA, SOURCE_UNAVAILABLE, REFERENCE_CHANGED }
}
