package com.gustler.backend.routecatalog.infrastructure.gbis;

import com.gustler.backend.gbis.api.GbisRouteLineSource;
import com.gustler.backend.routecatalog.domain.geometry.RouteGeometrySource;
import com.gustler.backend.routecatalog.domain.geometry.RouteShape;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class GbisRouteGeometrySource implements RouteGeometrySource {
    private final GbisRouteLineSource lines;
    private final Clock clock;

    public GbisRouteGeometrySource(GbisRouteLineSource lines, Clock clock) {
        this.lines = lines;
        this.clock = clock;
    }

    @Override
    public Optional<Read> read(String sourceRouteId) {
        try {
            return lines.read(sourceRouteId).map(points -> new Read(new RouteShape(points.stream()
                .map(point -> new RouteShape.Point(point.x(), point.y())).toList()), OffsetDateTime.now(clock)));
        } catch (IllegalArgumentException unreadable) { return Optional.empty(); }
    }
}
