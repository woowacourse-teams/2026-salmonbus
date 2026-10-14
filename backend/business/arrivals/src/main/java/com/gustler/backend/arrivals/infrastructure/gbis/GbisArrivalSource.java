package com.gustler.backend.arrivals.infrastructure.gbis;

import com.gustler.backend.arrival.contract.ArrivalSnapshot.Prediction;
import com.gustler.backend.arrival.contract.ArrivalSnapshot.RouteArrival;
import com.gustler.backend.arrivals.domain.ArrivalSource;
import com.gustler.backend.arrivals.domain.StationId;
import com.gustler.backend.gbis.api.GbisApiCaller;
import com.gustler.backend.gbis.api.GbisRawResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 외부 응답은 한 정류장의 완결된 수신 결과로 검증한다. 해석 실패를 빈 목록으로 바꾸지 않는다. */
public final class GbisArrivalSource implements ArrivalSource {
    public static final String PATH = "/busarrivalservice/v2/getBusArrivalListv2";
    private static final Set<Integer> SEAT_TYPES = Set.of(11, 12, 14, 16, 17, 21, 22);
    private static final Set<Integer> CROWD_TYPES = Set.of(13, 15, 23);
    private final GbisApiCaller caller;
    private final ObjectMapper json;
    private final Clock clock;

    public GbisArrivalSource(GbisApiCaller caller, ObjectMapper json, Clock clock) {
        this.caller = caller; this.json = json; this.clock = clock;
    }

    @Override
    public Result fetch(String stationId, String keyAlias) {
        StationId station = new StationId(stationId);
        var http = caller.getByStationWithMetadata(PATH, station.value(), keyAlias);
        GbisRawResponse raw = http.response();
        Instant receivedAt = clock.instant();
        Duration retryAfter = retryAfter(http.retryAfter(), receivedAt);
        if (raw instanceof GbisRawResponse.PortalRejected denied) {
            return new Failed(switch (denied.reason()) {
                case DAILY_QUOTA_EXCEEDED -> Reason.DAILY_QUOTA_EXCEEDED;
                case PER_SECOND_QUOTA_EXCEEDED -> Reason.RATE_LIMITED;
                case UNREGISTERED_KEY, EXPIRED_KEY -> Reason.KEY_REJECTED;
                default -> http.httpStatus() == 429 ? Reason.RATE_LIMITED : Reason.UPSTREAM_ERROR;
            }, retryAfter);
        }
        if (http.httpStatus() == 429) return new Failed(Reason.RATE_LIMITED, retryAfter);
        if (http.httpStatus() == 401 || http.httpStatus() == 403) return new Failed(Reason.KEY_REJECTED, retryAfter);
        if (http.httpStatus() >= 400) return new Failed(Reason.UPSTREAM_ERROR, retryAfter);
        if (!(raw instanceof GbisRawResponse.Received received)) return new Failed(Reason.TRANSPORT_ERROR);
        try {
            JsonNode response = json.readTree(received.body()).path("response");
            Integer resultCode = number(response.path("msgHeader"), "resultCode");
            if (resultCode == null) return new Failed(Reason.UNREADABLE_RESPONSE);
            if (resultCode != 0) return new Failed(Reason.UPSTREAM_ERROR);
            JsonNode body = response.path("msgBody");
            if (!body.isObject()) return new Failed(Reason.UNREADABLE_RESPONSE);
            JsonNode rows = body.path("busArrivalList");
            List<RouteArrival> routes = new ArrayList<>();
            if (rows.isObject()) routes.add(route(rows, stationId));
            else if (rows.isArray()) for (JsonNode row : rows) routes.add(route(row, stationId));
            else if (!rows.isMissingNode() && !rows.isNull()) return new Failed(Reason.UNREADABLE_RESPONSE);
            Set<String> identities = new HashSet<>();
            for (RouteArrival route : routes) {
                if (!identities.add(route.routeId() + "/" + route.sourceStopOrder())) {
                    return new Failed(Reason.UNREADABLE_RESPONSE);
                }
            }
            return new Success(receivedAt, text(response.path("msgHeader"), "queryTime"), routes);
        } catch (RuntimeException unreadable) {
            return new Failed(Reason.UNREADABLE_RESPONSE);
        }
    }

    private RouteArrival route(JsonNode row, String stationId) {
        if (!stationId.equals(text(row, "stationId"))) throw new IllegalArgumentException("다른 정류장 응답");
        Integer order = number(row, "staOrder");
        Integer type = number(row, "routeTypeCd");
        List<Prediction> predictions = new ArrayList<>();
        for (int ordinal = 1; ordinal <= 2; ordinal++) {
            String id = text(row, "vehId" + ordinal);
            Integer seconds = nonnegative(row, "predictTimeSec" + ordinal);
            Integer minutes = nonnegative(row, "predictTime" + ordinal);
            // 둘째 차량이 없을 때의 기본 0 값으로 가짜 차량을 생성하지 않는다.
            if (id == null && (seconds == null || seconds == 0) && (minutes == null || minutes == 0)) continue;
            if (seconds == null && minutes == null) throw new IllegalArgumentException("차량의 ETA 미해석");
            Integer seats = type != null && SEAT_TYPES.contains(type)
                ? nonnegative(row, "remainSeatCnt" + ordinal) : null;
            String seatReason = seats != null ? null
                : type == null || !SEAT_TYPES.contains(type) ? "UNSUPPORTED_ROUTE_TYPE" : "NOT_REPORTED";
            Integer congestion = type != null && CROWD_TYPES.contains(type)
                ? nonnegative(row, "crowded" + ordinal) : null;
            if (congestion != null && (congestion < 1 || congestion > 4)) congestion = null;
            Integer tagless = number(row, "taglessCd" + ordinal);
            predictions.add(new Prediction(id, ordinal, seconds, minutes,
                nonnegative(row, "locationNo" + ordinal), seats, seatReason, congestion,
                number(row, "lowPlate" + ordinal), tagless == null || (tagless != 0 && tagless != 1)
                    ? null : tagless == 1));
        }
        return new RouteArrival(text(row, "routeId"), text(row, "routeName"), order == null ? 0 : order,
            type, text(row, "routeDestId"), text(row, "routeDestName"), text(row, "flag"), predictions);
    }

    private static Integer nonnegative(JsonNode node, String field) {
        Integer value = number(node, field);
        return value == null || value < 0 ? null : value;
    }

    private static Duration retryAfter(String value, Instant receivedAt) {
        if (value == null || value.isBlank()) return null;
        try {
            Duration delay;
            if (value.strip().matches("[0-9]+")) delay = Duration.ofSeconds(Long.parseLong(value.strip()));
            else delay = Duration.between(receivedAt,
                ZonedDateTime.parse(value.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            delay.toNanos(); // Worker의 단조 시계로 표현할 수 없는 힌트는 무시한다.
            return delay.isNegative() ? Duration.ZERO : delay;
        } catch (RuntimeException invalid) { return null; }
    }

    private static Integer number(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) return null;
        try { return Integer.valueOf(value); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("숫자 필드 미해석"); }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        String text = value.asText().strip();
        return text.isEmpty() ? null : text;
    }
}
