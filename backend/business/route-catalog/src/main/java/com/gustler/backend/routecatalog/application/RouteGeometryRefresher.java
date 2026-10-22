package com.gustler.backend.routecatalog.application;

import com.gustler.backend.quota.api.ApiCallQuota;
import com.gustler.backend.routecatalog.api.RefreshRouteGeometry;
import com.gustler.backend.routecatalog.api.RefreshRouteGeometry.Outcome;
import com.gustler.backend.routecatalog.domain.geometry.RouteGeometrySource;
import com.gustler.backend.routecatalog.domain.geometry.RouteGeometrySource.Read;
import com.gustler.backend.routecatalog.domain.geometry.RouteGeometryStore;
import com.gustler.backend.routecatalog.domain.geometry.RouteGeometryStore.Reference;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** HTTP 대기 중에는 DB 트랜잭션을 열지 않고 정상 변경만 발행한다. */
@Component
public class RouteGeometryRefresher implements RefreshRouteGeometry {
    private final RouteGeometryStore store;
    private final RouteGeometrySource source;
    private final ApiCallQuota quota;

    public RouteGeometryRefresher(RouteGeometryStore store, RouteGeometrySource source, ApiCallQuota quota) {
        this.store = store;
        this.source = source;
        this.quota = quota;
    }

    @Override
    public Outcome refresh(String routeId, OffsetDateTime requestedAt) {
        Optional<Reference> reference = store.current(routeId);
        if (reference.isEmpty()) {
            return Outcome.NO_REFERENCE;
        }
        if (!quota.reserveRouteCatalog(requestedAt, 1)) {
            return Outcome.NO_QUOTA;
        }
        Optional<Read> read = source.read(reference.get().sourceRouteId());
        if (read.isEmpty()) {
            return Outcome.SOURCE_UNAVAILABLE;
        }
        return switch (store.publish(reference.get(), read.get())) {
            case UPDATED -> Outcome.UPDATED;
            case UNCHANGED -> Outcome.UNCHANGED;
            case REFERENCE_CHANGED -> Outcome.REFERENCE_CHANGED;
        };
    }
}
