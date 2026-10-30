package com.gustler.backend.api.route.application;

import com.gustler.backend.api.route.RouteNotFoundException;
import com.gustler.backend.api.route.domain.RouteServiceStatus;
import com.gustler.backend.routeoperations.api.RouteOperation;
import com.gustler.backend.routeoperations.api.RouteOperations;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class RouteServiceStatusQuery {
    private final ObjectProvider<RouteOperations> provider;
    private final Clock clock;

    public RouteServiceStatusQuery(ObjectProvider<RouteOperations> provider, Clock clock) {
        this.provider = provider;
        this.clock = clock;
    }

    public RouteServiceStatus get(String routeId) {
        Instant now = clock.instant();
        RouteOperations operations = provider.getIfAvailable();
        if (operations == null) {
            return new RouteServiceStatus(
                routeId, false, "ON", true, true, "NORMAL", "", "", now, now.plusSeconds(30));
        }
        RouteOperation row = operations.find(routeId).orElseThrow(RouteNotFoundException::new);
        return new RouteServiceStatus(
            routeId,
            true,
            row.state(),
            row.serviceEnabled(),
            row.collectionEnabled(),
            row.reason(),
            row.message(),
            row.sourceUrl(),
            now,
            now.plusSeconds(30));
    }
}
