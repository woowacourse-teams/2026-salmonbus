package com.gustler.backend.gbis.api;

import com.gustler.backend.gbis.api.GbisRawResponse.Received;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;
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

    /** 상류의 형상점 순번과 좌표다. 읽을 때 순번의 연속성과 좌표의 유한성을 검증한다. */
    public record Point(int lineSeq, double x, double y) {
    }

    public enum CaptureStatus { SUCCESS, HTTP_ERROR, TRANSPORT_FAILURE, INVALID_RESPONSE,
        SENSITIVE_RESPONSE, UNRECORDED_LEGACY }

    public record Capture(Optional<List<Point>> points, int httpStatus, CaptureStatus status, byte[] body) {
        public Capture {
            points = points.map(List::copyOf);
            body = body == null ? null : body.clone();
            if (status != CaptureStatus.SUCCESS && body != null)
                throw new IllegalArgumentException("Failed or withheld source cannot retain bytes");
        }
        @Override public byte[] body() { return body == null ? null : body.clone(); }
        @Override public String toString() {
            return "RouteLineCapture[httpStatus="+httpStatus+", status="+status+", pointCount="
                +points.map(List::size).orElse(0)+"]";
        }
    }

    /** Existing callers keep the same parsed result; capture preserves the original accepted response separately. */
    public Optional<List<Point>> read(String routeId) {
        return capture(routeId).points();
    }

    public Capture capture(String routeId) {
        GbisHttpResult http = caller.getWithMetadata(PATH, routeId, GbisKey.PRIMARY);
        if (http.httpStatus() == 0)
            return new Capture(Optional.empty(), 0, CaptureStatus.TRANSPORT_FAILURE, null);
        if (http.httpStatus() != 200)
            return new Capture(Optional.empty(), http.httpStatus(), CaptureStatus.HTTP_ERROR, null);
        Optional<List<Point>> parsed = parse(http.response(), routeId);
        if (parsed.isEmpty())
            return new Capture(parsed, http.httpStatus(), CaptureStatus.INVALID_RESPONSE, null);
        return switch (http.capture().disposition()) {
            case AVAILABLE -> new Capture(parsed, http.httpStatus(), CaptureStatus.SUCCESS, http.capture().body());
            case SENSITIVE_RESPONSE -> new Capture(parsed, http.httpStatus(), CaptureStatus.SENSITIVE_RESPONSE, null);
            case NOT_RETAINED -> new Capture(parsed, http.httpStatus(), CaptureStatus.UNRECORDED_LEGACY, null);
        };
    }

    private Optional<List<Point>> parse(GbisRawResponse raw, String routeId) {
        if (!(raw instanceof Received received)) return Optional.empty();
        try {
            JsonNode response = json.readTree(received.body()).path("response");
            if (!"0".equals(response.path("msgHeader").path("resultCode").asString(null))) {
                return Optional.empty();
            }
            JsonNode rows = response.path("msgBody").path("busRouteLineList");
            if (!rows.isArray() && !rows.isObject()) return Optional.empty();
            List<Point> points = pointsOf(rows, routeId);
            points.sort(Comparator.comparingInt(Point::lineSeq));
            if (points.size() < 2 || points.size() > 100_000
                    || IntStream.range(0, points.size()).anyMatch(index -> points.get(index).lineSeq() != index + 1))
                return Optional.empty();
            return Optional.of(List.copyOf(points));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    /** 호출부에서 배열 또는 단일 객체임을 확인한 형상점을 변환한다. */
    private static List<Point> pointsOf(JsonNode rows, String routeId) {
        if (rows.isArray()) {
            return StreamSupport.stream(rows.spliterator(), false)
                .map(row -> point(row, routeId))
                .collect(Collectors.toCollection(ArrayList::new));
        }
        return new ArrayList<>(List.of(point(rows, routeId)));
    }

    private static Point point(JsonNode row, String routeId) {
        String supplied = row.path("routeId").asString(null);
        if (supplied != null && !routeId.equals(supplied)) {
            throw new IllegalArgumentException("다른 노선의 형상");
        }
        final int sequence = Integer.parseInt(row.path("lineSeq").asString());
        final double x = Double.parseDouble(row.path("x").asString());
        final double y = Double.parseDouble(row.path("y").asString());
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException("유한하지 않은 좌표");
        }
        return new Point(sequence, x, y);
    }
}
