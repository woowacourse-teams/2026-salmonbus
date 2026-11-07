package com.gustler.backend.routecatalog.infrastructure.gbis;

import com.gustler.backend.gbis.api.GbisRouteLineSource;
import com.gustler.backend.routecatalog.domain.geometry.RouteGeometrySource;
import com.gustler.backend.routecatalog.domain.geometry.RouteGeometrySource.Read;
import com.gustler.backend.routecatalog.domain.geometry.RouteShape;
import com.gustler.backend.routecatalog.domain.geometry.RouteShape.Point;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * GBIS 도로 형상점을 카탈로그의 형상 입력으로 변환한다.
 * 원천을 읽지 못한 상태를 정류장 직선으로 대체하지 않는다.
 */
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
        return capture(sourceRouteId).read();
    }

    @Override
    public Capture capture(String sourceRouteId) {
        var captured = lines.capture(sourceRouteId);
        var acquiredAt = clock.instant();
        Optional<Read> read;
        var status = com.gustler.backend.routegeometry.api.RouteSourceReceipt.Status.valueOf(captured.status().name());
        byte[] body = captured.body();
        try {
            read = captured.points().map(points -> new Read(new RouteShape(points.stream()
                .map(point -> new Point(point.x(), point.y())).toList()),
                OffsetDateTime.ofInstant(acquiredAt, java.time.ZoneOffset.UTC)));
        } catch (IllegalArgumentException invalidShape) {
            read = Optional.empty();
            status = com.gustler.backend.routegeometry.api.RouteSourceReceipt.Status.INVALID_RESPONSE;
            body = null;
        }
        var receipt = new com.gustler.backend.routegeometry.api.RouteSourceReceipt(
            "GBIS_API", "ROUTE_LINE", sourceRouteId.matches("[0-9]{1,20}") ? sourceRouteId : "UNSPECIFIED",
            status, captured.httpStatus() == 0 ? null : captured.httpStatus(), acquiredAt, "NOT_PROVIDED",
            com.gustler.backend.routegeometry.api.RouteSourceReceipt.CoordinateSystem.WGS84_ASSUMED, body);
        return new Capture(read, java.util.List.of(receipt));
    }
}
