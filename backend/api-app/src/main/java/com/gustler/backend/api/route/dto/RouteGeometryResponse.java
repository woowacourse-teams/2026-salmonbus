package com.gustler.backend.api.route.dto;

import com.gustler.backend.api.route.domain.RouteGeometry;
import java.util.List;

/** 네이버 버스 노선 응답의 points/busStops/point/boundary 구성을 필요한 필드로 한정한다. */
public record RouteGeometryResponse(String routeId, String referenceVersionId, String geometryRevision,
    String name, String startPoint, String endPoint, String status, String reason, String coordinateSystem,
    TurningPoint turningPoint, List<BusStop> busStops, List<RouteGeometry.Point> points, RouteGeometry.Boundary boundary) {

    public record TurningPoint(int stopSequence) { }
    public record BusStop(String stationId, int stopSequence, String name, String direction,
                          boolean boardingAllowed, RouteGeometry.Point point) { }

    public static RouteGeometryResponse from(RouteGeometry geometry) {
        return new RouteGeometryResponse(geometry.routeId(), Long.toString(geometry.referenceVersionId()), geometry.revision(),
            geometry.name(), geometry.startPoint(), geometry.endPoint(), geometry.status().name(), geometry.reason(),
            RouteGeometry.COORDINATE_SYSTEM, geometry.turnSequence() == null ? null : new TurningPoint(geometry.turnSequence()),
            geometry.busStops().stream().map(stop -> new BusStop(stop.stationId(), stop.stopSequence(), stop.name(),
                stop.direction(), stop.boardingAllowed(), stop.point())).toList(), geometry.points(), geometry.boundary());
    }
}
