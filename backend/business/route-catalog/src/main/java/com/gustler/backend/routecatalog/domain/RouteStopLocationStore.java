package com.gustler.backend.routecatalog.domain;

import java.time.OffsetDateTime;

public interface RouteStopLocationStore {
    Changes record(long versionId, RouteStops stops, OffsetDateTime observedAt);

    record Changes(int firstCaptured, int changed, int missing) {
        public boolean hasWrites() {
            return firstCaptured + changed > 0;
        }
    }
}
