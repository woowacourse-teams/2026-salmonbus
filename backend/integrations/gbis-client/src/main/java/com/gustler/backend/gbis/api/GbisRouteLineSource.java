package com.gustler.backend.gbis.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 경유 정류장 좌표와 별도로 제공되는 도로 형상점 목록. */
@Component
public class GbisRouteLineSource {
    public static final String PATH = "/busrouteservice/v2/getBusRouteLineListv2";
    private final GbisApiCaller caller;
    private final ObjectMapper json;

    public GbisRouteLineSource(GbisApiCaller caller, ObjectMapper json) {
        this.caller = caller;
        this.json = json;
    }

    public record Point(int lineSeq, double x, double y) { }

    public Optional<List<Point>> read(String routeId) {
        var http = caller.getWithMetadata(PATH, routeId, GbisKey.PRIMARY);
        if (http.httpStatus() != 200 || !(http.response() instanceof GbisRawResponse.Received received)) return Optional.empty();
        try {
            var response = json.readTree(received.body()).path("response");
            if (!"0".equals(response.path("msgHeader").path("resultCode").asString(null))) return Optional.empty();
            JsonNode rows = response.path("msgBody").path("busRouteLineList");
            List<Point> points = new ArrayList<>();
            if (rows.isArray()) for (var row : rows) points.add(point(row, routeId));
            else if (rows.isObject()) points.add(point(rows, routeId));
            else return Optional.empty();
            points.sort(Comparator.comparingInt(Point::lineSeq));
            if (points.size() < 2 || points.size() > 100_000) return Optional.empty();
            for (int index = 0; index < points.size(); index++) {
                if (points.get(index).lineSeq() != index + 1) return Optional.empty();
            }
            return Optional.of(List.copyOf(points));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    private static Point point(JsonNode row, String routeId) {
        String supplied = row.path("routeId").asString(null);
        if (supplied != null && !routeId.equals(supplied)) throw new IllegalArgumentException("다른 노선의 형상");
        int sequence = Integer.parseInt(row.path("lineSeq").asString());
        double x = Double.parseDouble(row.path("x").asString());
        double y = Double.parseDouble(row.path("y").asString());
        if (!Double.isFinite(x) || !Double.isFinite(y)) throw new IllegalArgumentException("유한하지 않은 좌표");
        return new Point(sequence, x, y);
    }
}
