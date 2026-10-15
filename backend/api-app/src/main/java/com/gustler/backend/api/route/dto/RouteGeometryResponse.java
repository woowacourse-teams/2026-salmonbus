package com.gustler.backend.api.route.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gustler.backend.api.route.domain.RouteGeometry;
import com.gustler.backend.api.route.domain.RoutePaths;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 네이버 버스 노선 응답의 points/busStops/point/boundary 구성을 필요한 필드로 한정한다. */
@Schema(requiredProperties = {"routeId", "referenceVersionId", "geometryRevision", "name", "startPoint", "endPoint",
    "status", "reason", "coordinateSystem", "turningPoint", "busStops", "points", "paths", "boundary"},
    additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record RouteGeometryResponse(
    @Schema(description = "9자리 GBIS 노선 ID", pattern = "^[0-9]{9}$") String routeId,
    @Schema(description = "현재 노선 판본. 문자열로 유지하고 정류장 ETA 요청에 전달", pattern = "^[1-9][0-9]*$")
    String referenceVersionId,
    @Schema(description = "형상·정류장 자료와 방향 분리 규칙을 반영한 캐시 판본", pattern = "^[0-9a-f]{64}$")
    String geometryRevision,
    @Schema(description = "노선 번호 또는 표시명") String name,
    @Schema(description = "기점 이름") String startPoint,
    @Schema(description = "회차 방면 종점 이름") String endPoint,
    @Schema(description = "전체 형상과 정류장 좌표의 제공 상태. paths의 존재 여부는 별도로 확인",
        allowableValues = {"READY", "PARTIAL", "UNAVAILABLE"}) String status,
    @Schema(description = "정상이면 null. NOT_COLLECTED 또는 MISSING_STOP_COORDINATES", nullable = true,
        allowableValues = {"NOT_COLLECTED", "MISSING_STOP_COORDINATES"}) String reason,
    @Schema(description = "x를 경도, y를 위도로 표시하는 가정이며 원천 좌표계 확인 완료를 뜻하지 않음",
        allowableValues = "WGS84_ASSUMED") String coordinateSystem,
    @Schema(description = "회차 정류장의 방문 순번. 없으면 null", nullable = true) TurningPoint turningPoint,
    @Schema(description = "방문 순서의 정류장 목록. 같은 stationId의 반복 방문을 합치지 않음") List<BusStop> busStops,
    @Schema(description = "원본 운행 순서의 전체 도로 형상. 미수집이면 빈 배열이며 정류장 직선으로 대체하지 않음")
    List<Point> points,
    @Schema(description = "상·하행 경로를 함께 제공. 분리 근거가 없으면 null이며 전체 points는 유지. 방향 선택에 재조회 불필요",
        nullable = true) Paths paths,
    @Schema(description = "전체 경로와 제공 가능한 정류장 좌표를 포함하는 영역. 좌표가 전혀 없으면 null", nullable = true)
    Boundary boundary
) {

    @Schema(name = "RouteTurningPoint", requiredProperties = "stopSequence",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record TurningPoint(@Schema(description = "회차 정류장 방문 순번. 형상 배열 인덱스가 아님", minimum = "1") int stopSequence) { }

    @Schema(name = "RouteGeometryPaths", requiredProperties = {"UP", "DOWN"},
        description = "실제 운행 순서의 양방향 경로. 회차 경계점을 공유하며 하행은 상행의 역순으로 만들지 않음",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Paths(@JsonProperty("UP") @Schema(description = "기점에서 회차 지점까지") Path up,
                        @JsonProperty("DOWN") @Schema(description = "회차 지점에서 돌아오는 경로") Path down) { }

    @Schema(name = "RouteGeometryPath", requiredProperties = "points",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Path(@ArraySchema(minItems = 2, schema = @Schema(implementation = Point.class),
        arraySchema = @Schema(description = "운행 순서의 형상점. 서로 다른 좌표가 두 개 이상이고 원본의 반복 좌표는 유지"))
        List<Point> points) { }

    @Schema(name = "RouteGeometryStop", requiredProperties = {"stationId", "stopSequence", "name", "direction", "boardingAllowed", "point"},
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record BusStop(
        @Schema(description = "정류장 ID. 노선 안에서 여러 번 등장할 수 있음") String stationId,
        @Schema(description = "노선 판본 안의 1부터 시작하는 방문 순번", minimum = "1") int stopSequence,
        @Schema(description = "정류장 이름") String name,
        @Schema(description = "해당 방문의 방향. 회차 방문까지 UP이며 다음 방문부터 DOWN", allowableValues = {"UP", "DOWN"}) String direction,
        @Schema(description = "이 방문에서 승차 가능한지 여부") boolean boardingAllowed,
        @Schema(description = "정류장 마커 좌표. 미제공 또는 읽을 수 없는 좌표이면 null", nullable = true) Point point
    ) { }

    @Schema(name = "RouteGeometryPoint", requiredProperties = {"x", "y"},
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Point(@Schema(description = "경도로 해석하는 X좌표", minimum = "-180", maximum = "180") double x,
                        @Schema(description = "위도로 해석하는 Y좌표", minimum = "-90", maximum = "90") double y) { }

    @Schema(name = "RouteGeometryBoundary", requiredProperties = {"left", "top", "right", "bottom"},
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Boundary(@Schema(description = "최소 X좌표") double left, @Schema(description = "최대 Y좌표") double top,
                           @Schema(description = "최대 X좌표") double right, @Schema(description = "최소 Y좌표") double bottom) { }

    public static RouteGeometryResponse from(RouteGeometry geometry) {
        RoutePaths paths = geometry.paths();
        RouteGeometry.Boundary boundary = geometry.boundary();
        return new RouteGeometryResponse(geometry.routeId(), Long.toString(geometry.referenceVersionId()), geometry.revision(),
            geometry.name(), geometry.startPoint(), geometry.endPoint(), geometry.status().name(), geometry.reason(),
            RouteGeometry.COORDINATE_SYSTEM, geometry.turnSequence() == null ? null : new TurningPoint(geometry.turnSequence()),
            geometry.busStops().stream().map(stop -> new BusStop(stop.stationId(), stop.stopSequence(), stop.name(),
                stop.direction(), stop.boardingAllowed(), pointOf(stop.point()))).toList(), pointsOf(geometry.points()),
            paths == null ? null : new Paths(new Path(pointsOf(paths.up().points())), new Path(pointsOf(paths.down().points()))),
            boundary == null ? null : new Boundary(boundary.left(), boundary.top(), boundary.right(), boundary.bottom()));
    }

    private static Point pointOf(RouteGeometry.Point point) { return point == null ? null : new Point(point.x(), point.y()); }
    private static List<Point> pointsOf(List<RouteGeometry.Point> points) { return points.stream().map(RouteGeometryResponse::pointOf).toList(); }
}
