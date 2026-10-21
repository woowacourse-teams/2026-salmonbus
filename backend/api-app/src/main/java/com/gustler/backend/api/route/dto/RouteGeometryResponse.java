package com.gustler.backend.api.route.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gustler.backend.api.route.domain.RouteGeometry;
import com.gustler.backend.api.route.domain.RoutePaths;
import com.gustler.backend.api.route.dto.RouteGeometryResponse.Boundary;
import com.gustler.backend.api.route.dto.RouteGeometryResponse.BusStop;
import com.gustler.backend.api.route.dto.RouteGeometryResponse.Paths;
import com.gustler.backend.api.route.dto.RouteGeometryResponse.Point;
import com.gustler.backend.api.route.dto.RouteGeometryResponse.TurningPoint;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 네이버 버스 노선 응답의 points/busStops/point/boundary 구성을 필요한 필드로 한정한다. */
@Schema(requiredProperties = {"routeId", "referenceVersionId", "geometryRevision", "name", "startPoint", "endPoint",
    "status", "reason", "coordinateSystem", "turningPoint", "busStops", "points", "paths", "boundary"},
    additionalProperties = AdditionalPropertiesValue.FALSE)
public record RouteGeometryResponse(
    @Schema(description = "9자리 GBIS 노선 ID", pattern = "^[0-9]{9}$")
    String routeId,
    @Schema(description = "현재 노선 판본. 문자열로 유지하고 stopSequence와 함께 정류장 ETA 요청에 전달. 409이면 판본 없이 geometry 재조회",
        pattern = "^[1-9][0-9]*$")
    String referenceVersionId,
    @Schema(description = "형상·정류장 자료와 방향 분리 규칙을 반영한 캐시 판본. referenceVersionId가 같아도 좌표가 바뀌면 변경",
        pattern = "^[0-9a-f]{64}$")
    String geometryRevision,
    @Schema(description = "노선 번호 또는 표시명")
    String name,
    @Schema(description = "기점 이름")
    String startPoint,
    @Schema(description = "회차 방면 종점 이름")
    String endPoint,
    @Schema(description =
        "READY=형상과 모든 정류장 좌표, PARTIAL=형상은 있고 일부 정류장 point=null, UNAVAILABLE=현재 판본 형상 미수집. READY여도 paths=null 가능",
        allowableValues = {"READY", "PARTIAL", "UNAVAILABLE"})
    String status,
    @Schema(description = "READY이면 null. NOT_COLLECTED=현재 판본 형상 미수집, MISSING_STOP_COORDINATES=일부 정류장 좌표 누락",
        nullable = true,
        allowableValues = {"NOT_COLLECTED", "MISSING_STOP_COORDINATES"})
    String reason,
    @Schema(description = "x를 경도, y를 위도로 표시하는 가정이며 원천 좌표계 확인 완료를 뜻하지 않음",
        allowableValues = "WGS84_ASSUMED")
    String coordinateSystem,
    @Schema(description = "회차 정류장의 방문 순번. 형상 배열 인덱스가 아니며, 값이 있어도 paths 분리 근거가 충분하다는 뜻은 아님. 없으면 null", nullable = true)
    TurningPoint turningPoint,
    @Schema(description = "방문 순서의 정류장 마커 목록. 같은 stationId의 반복 방문을 stopSequence로 구분하며 합치지 않음. 전체 형상과 점 개수가 다름")
    List<BusStop> busStops,
    @Schema(description = "원본 운행 순서의 전체 도로 형상. 정류장 마커와 별도 자료이며 정류장 직선으로 대체하지 않음. 미수집이면 빈 배열, paths=null이어도 형상은 유지")
    List<Point> points,
    @Schema(description =
        "분리 근거가 확인되면 UP/DOWN을 함께 제공. 회차·방문 순서 대응이 부족하거나 모호하면 READY여도 null이며 전체 points는 유지. 방향 선택에 재조회 불필요",
        nullable = true)
    Paths paths,
    @Schema(description = "전체 경로와 제공 가능한 정류장 좌표를 포함하는 영역. 좌표가 전혀 없으면 null", nullable = true)
    Boundary boundary
) {
    /** 원본 참조와 분리하고 기존 null·원소·순서를 보존한다. */
    public RouteGeometryResponse {
        if (busStops != null) {
            busStops = Collections.unmodifiableList(new ArrayList<>(busStops));
        }
        if (points != null) {
            points = Collections.unmodifiableList(new ArrayList<>(points));
        }
    }

    @Schema(name = "RouteTurningPoint", requiredProperties = "stopSequence",
        additionalProperties = AdditionalPropertiesValue.FALSE)
    public record TurningPoint(@Schema(description = "회차 정류장 방문 순번. 형상 배열 인덱스가 아님", minimum = "1")
        int stopSequence) {
    }

    @Schema(name = "RouteGeometryPaths", requiredProperties = {"UP", "DOWN"},
        description = "실제 운행 순서의 양방향 경로. 회차 경계점을 공유하며 하행은 상행의 역순으로 만들지 않음",
        additionalProperties = AdditionalPropertiesValue.FALSE)
    public record Paths(@JsonProperty("UP")
        @Schema(description = "기점에서 회차 지점까지")
        Path up,
        @JsonProperty("DOWN")
        @Schema(description = "회차 지점에서 돌아오는 경로")
        Path down) {
    }

    @Schema(name = "RouteGeometryPath", requiredProperties = "points",
        additionalProperties = AdditionalPropertiesValue.FALSE)
    public record Path(@ArraySchema(minItems = 2, schema = @Schema(implementation = Point.class),
        arraySchema = @Schema(description = "운행 순서의 형상점. 서로 다른 좌표가 두 개 이상이고 원본의 반복 좌표는 유지"))
        List<Point> points) {
        /** 원본 참조와 분리하고 기존 null·원소·순서를 보존한다. */
        public Path {
            if (points != null) {
                points = Collections.unmodifiableList(new ArrayList<>(points));
            }
        }
    }

    @Schema(name = "RouteGeometryStop",
        requiredProperties = {"stationId", "stopSequence", "name", "direction", "boardingAllowed", "point"},
        additionalProperties = AdditionalPropertiesValue.FALSE)
    public record BusStop(
        @Schema(description = "정류장 ID. 노선 안에서 여러 번 등장할 수 있음")
        String stationId,
        @Schema(description = "노선 판본 안의 1부터 시작하는 방문 순번", minimum = "1")
        int stopSequence,
        @Schema(description = "정류장 이름")
        String name,
        @Schema(description = "해당 방문의 방향. 회차 방문까지 UP이며 다음 방문부터 DOWN", allowableValues = {"UP", "DOWN"})
        String direction,
        @Schema(description = "이 방문에서 승차 가능한지 여부")
        boolean boardingAllowed,
        @Schema(description = "정류장 마커 좌표(x=경도,y=위도). 미제공·유효하지 않은 좌표이면 null로 유지하고 마커를 생략. 도로 형상 points와 별개",
            nullable = true)
        Point point
    ) {
    }

    @Schema(name = "RouteGeometryPoint", requiredProperties = {"x", "y"},
        additionalProperties = AdditionalPropertiesValue.FALSE)
    public record Point(@Schema(description = "경도로 해석하는 X좌표", minimum = "-180", maximum = "180")
        double x,
        @Schema(description = "위도로 해석하는 Y좌표", minimum = "-90", maximum = "90")
        double y) {
    }

    @Schema(name = "RouteGeometryBoundary", requiredProperties = {"left", "top", "right", "bottom"},
        additionalProperties = AdditionalPropertiesValue.FALSE)
    public record Boundary(@Schema(description = "최소 X좌표")
        double left, @Schema(description = "최대 Y좌표")
        double top,
        @Schema(description = "최대 X좌표")
        double right, @Schema(description = "최소 Y좌표")
        double bottom) {
    }

    public static RouteGeometryResponse from(RouteGeometry geometry) {
        RoutePaths paths = geometry.paths();
        RouteGeometry.Boundary boundary = geometry.boundary();
        return new RouteGeometryResponse(geometry.routeId(), Long.toString(geometry.referenceVersionId()),
            geometry.revision(),
            geometry.name(), geometry.startPoint(), geometry.endPoint(), geometry.status().name(), geometry.reason(),
            RouteGeometry.COORDINATE_SYSTEM, turningPointOf(geometry.turnSequence()),
            geometry.busStops()
                .stream()
                .map(stop -> new BusStop(stop.stationId(), stop.stopSequence(), stop.name(),
                    stop.direction(), stop.boardingAllowed(), pointOf(stop.point())))
                .toList(), pointsOf(geometry.points()),
            pathsOf(paths), boundaryOf(boundary));
    }

    private static TurningPoint turningPointOf(Integer sequence) {
        if (sequence == null) {
            return null;
        }
        return new TurningPoint(sequence);
    }

    private static Paths pathsOf(RoutePaths paths) {
        if (paths == null) {
            return null;
        }
        RoutePaths.Path up = paths.up();
        RoutePaths.Path down = paths.down();
        return new Paths(new Path(pointsOf(up.points())), new Path(pointsOf(down.points())));
    }

    private static Boundary boundaryOf(RouteGeometry.Boundary boundary) {
        if (boundary == null) {
            return null;
        }
        return new Boundary(boundary.left(), boundary.top(), boundary.right(), boundary.bottom());
    }

    private static Point pointOf(RouteGeometry.Point point) {
        if (point == null) {
            return null;
        }
        return new Point(point.x(), point.y());
    }

    private static List<Point> pointsOf(List<RouteGeometry.Point> points) {
        return points.stream()
            .map(RouteGeometryResponse::pointOf)
            .toList();
    }
}
