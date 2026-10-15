package com.gustler.localgbis;

import com.github.tomakehurst.wiremock.common.Json;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 정류장 목록과 독립적으로 확보한 도로 형상을 해당 카탈로그 판본에 연결한다. */
final class RouteShapes {
    private RouteShapes() { }

    static Map<String, List<Point>> read(Path file, ReplayEngine.Dataset dataset) throws Exception {
        require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) <= 67_108_864,
            "도로 형상 파일을 확인해 주세요.");
        var document = Json.node(Files.readString(file));
        require("gbis-route-shapes-v1".equals(document.path("version").asText())
            && document.path("routes").isArray(), "도로 형상 자료 버전과 목록을 확인해 주세요.");
        Map<String, String> references = dataset.routes().stream()
            .collect(Collectors.toMap(ReplayEngine.Route::routeId, ReplayEngine.Route::contentDigest));
        Map<String, List<Point>> indexed = new LinkedHashMap<>();
        for (var shape : document.path("routes")) {
            String routeId = shape.path("routeId").asText();
            String reference = shape.path("referenceDigest").asText();
            require(routeId.matches("[0-9]{9}") && references.containsKey(routeId)
                && !indexed.containsKey(routeId), "형상 노선이 없거나 중복됩니다.");
            require(reference.matches("[0-9a-f]{64}") && reference.equals(references.get(routeId)),
                "도로 형상과 정류장 카탈로그 판본이 다릅니다.");
            var rows = shape.path("points");
            require(rows.isArray() && rows.size() >= 2 && rows.size() <= 100_000,
                "도로 형상점 개수를 확인해 주세요.");
            List<Point> points = new ArrayList<>(rows.size());
            for (var row : rows) {
                require(row.path("x").isNumber() && row.path("y").isNumber(),
                    "도로 형상 좌표는 숫자여야 합니다.");
                final double x = row.path("x").doubleValue();
                final double y = row.path("y").doubleValue();
                require(Double.isFinite(x) && Double.isFinite(y) && x != 0 && y != 0 && x >= -180 && x <= 180
                    && y >= -90 && y <= 90, "도로 형상 경위도 범위를 확인해 주세요.");
                points.add(new Point(x, y));
            }
            require(points.stream().distinct().limit(2).count() >= 2,
                "서로 다른 도로 형상점이 두 개 이상 필요합니다.");
            indexed.put(routeId, List.copyOf(points));
        }
        require(indexed.keySet().equals(references.keySet()), "카탈로그의 모든 노선에 도로 형상이 필요합니다.");
        return Map.copyOf(indexed);
    }

    private static void require(final boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    record Point(double x, double y) { }
}
