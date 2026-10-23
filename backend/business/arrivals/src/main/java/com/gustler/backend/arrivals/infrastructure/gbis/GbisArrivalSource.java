package com.gustler.backend.arrivals.infrastructure.gbis;

import com.gustler.backend.arrival.contract.ArrivalSnapshot.Prediction;
import com.gustler.backend.arrival.contract.ArrivalSnapshot.RouteArrival;
import com.gustler.backend.arrivals.domain.ArrivalSource;
import com.gustler.backend.arrivals.domain.ArrivalSource.Failed;
import com.gustler.backend.arrivals.domain.ArrivalSource.Reason;
import com.gustler.backend.arrivals.domain.ArrivalSource.Success;
import com.gustler.backend.arrivals.domain.StationId;
import com.gustler.backend.gbis.api.GbisApiCaller;
import com.gustler.backend.gbis.api.GbisHttpResult;
import com.gustler.backend.gbis.api.GbisRawResponse;
import com.gustler.backend.gbis.api.GbisRawResponse.PortalRejected;
import com.gustler.backend.gbis.api.GbisRawResponse.Received;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.springframework.http.HttpStatus;
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
        this.caller = caller;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public ArrivalSource.Result fetch(String stationId, String keyAlias) {
        StationId station = new StationId(stationId);
        GbisHttpResult http = caller.getByStationWithMetadata(PATH, station.value(), keyAlias);
        GbisRawResponse raw = http.response();
        Instant receivedAt = clock.instant();
        Duration retryAfter = retryAfter(http.retryAfter(), receivedAt);
        if (raw instanceof PortalRejected denied) {
            return new Failed(switch (denied.reason()) {
                case DAILY_QUOTA_EXCEEDED -> Reason.DAILY_QUOTA_EXCEEDED;
                case PER_SECOND_QUOTA_EXCEEDED -> Reason.RATE_LIMITED;
                case UNREGISTERED_KEY, EXPIRED_KEY -> Reason.KEY_REJECTED;
                default -> upstreamReason(http.httpStatus());
            }, retryAfter);
        }
        if (http.httpStatus() == HttpStatus.TOO_MANY_REQUESTS.value()) {
            return new Failed(Reason.RATE_LIMITED, retryAfter);
        }
        if (http.httpStatus() == HttpStatus.UNAUTHORIZED.value()
            || http.httpStatus() == HttpStatus.FORBIDDEN.value()) {
            return new Failed(Reason.KEY_REJECTED, retryAfter);
        }
        if (http.httpStatus() >= HttpStatus.BAD_REQUEST.value()) {
            return new Failed(Reason.UPSTREAM_ERROR, retryAfter);
        }
        if (!(raw instanceof Received received)) {
            return new Failed(Reason.TRANSPORT_ERROR);
        }
        try {
            JsonNode response = json.readTree(received.body()).path("response");
            Integer resultCode = number(response.path("msgHeader"), "resultCode");
            if (resultCode == null) {
                return new Failed(Reason.UNREADABLE_RESPONSE);
            }
            if (resultCode != 0) {
                return new Failed(Reason.UPSTREAM_ERROR);
            }
            JsonNode body = response.path("msgBody");
            if (!body.isObject()) {
                return new Failed(Reason.UNREADABLE_RESPONSE);
            }
            JsonNode rows = body.path("busArrivalList");
            List<RouteArrival> routes = routesOf(rows, stationId);
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

    private static Reason upstreamReason(final int httpStatus) {
        if (httpStatus == HttpStatus.TOO_MANY_REQUESTS.value()) {
            return Reason.RATE_LIMITED;
        }
        return Reason.UPSTREAM_ERROR;
    }

    private List<RouteArrival> routesOf(JsonNode rows, String stationId) {
        if (rows.isObject()) {
            return new ArrayList<>(List.of(route(rows, stationId)));
        }
        if (rows.isArray()) {
            return StreamSupport.stream(rows.spliterator(), false)
                .map(row -> route(row, stationId))
                .collect(Collectors.toCollection(ArrayList::new));
        }
        if (!rows.isMissingNode() && !rows.isNull()) {
            throw new IllegalArgumentException("도착정보 목록 형식 미해석");
        }
        return new ArrayList<>();
    }

    private RouteArrival route(JsonNode row, String stationId) {
        if (!stationId.equals(text(row, "stationId"))) {
            throw new IllegalArgumentException("다른 정류장 응답");
        }
        Integer order = number(row, "staOrder");
        Integer type = number(row, "routeTypeCd");
        List<Prediction> predictions = new ArrayList<>();
        for (int ordinal = 1; ordinal <= 2; ordinal++) {
            String id = text(row, "vehId" + ordinal);
            Integer seconds = nonnegative(row, "predictTimeSec" + ordinal);
            Integer minutes = nonnegative(row, "predictTime" + ordinal);
            // 둘째 차량이 없을 때의 기본 0 값으로 차량을 생성하지 않는다.
            if (hasNoVehicleData(id, seconds, minutes)) {
                continue;
            }
            if (seconds == null && minutes == null) {
                throw new IllegalArgumentException("차량의 ETA 미해석");
            }
            Integer seats = supportedValue(row, "remainSeatCnt" + ordinal, type, SEAT_TYPES);
            String seatReason = seatUnavailableReason(seats, type);
            Integer congestion = supportedValue(row, "crowded" + ordinal, type, CROWD_TYPES);
            if (isUnsupportedCongestion(congestion)) {
                congestion = null;
            }
            Integer tagless = number(row, "taglessCd" + ordinal);
            predictions.add(new Prediction(id, ordinal, seconds, minutes,
                nonnegative(row, "locationNo" + ordinal), seats, seatReason, congestion,
                number(row, "lowPlate" + ordinal), taglessOf(tagless)));
        }
        return new RouteArrival(text(row, "routeId"), text(row, "routeName"), stopOrderOf(order),
            type, text(row, "routeDestId"), text(row, "routeDestName"), text(row, "flag"), predictions);
    }

    private static boolean hasNoVehicleData(String id, Integer seconds, Integer minutes) {
        if (id != null || hasArrivalTime(seconds)) {
            return false;
        }
        return !hasArrivalTime(minutes);
    }

    private static boolean hasArrivalTime(Integer value) {
        return value != null && value != 0;
    }

    private static boolean isUnsupportedCongestion(Integer congestion) {
        if (congestion == null) {
            return false;
        }
        return congestion < 1 || congestion > 4;
    }

    private static Integer supportedValue(
        JsonNode row,
        String field,
        Integer type,
        Set<Integer> supportedTypes
    ) {
        if (type != null && supportedTypes.contains(type)) {
            return nonnegative(row, field);
        }
        return null;
    }

    private static String seatUnavailableReason(Integer seats, Integer type) {
        if (seats != null) {
            return null;
        }
        if (type == null || !SEAT_TYPES.contains(type)) {
            return "UNSUPPORTED_ROUTE_TYPE";
        }
        return "NOT_REPORTED";
    }

    private static Boolean taglessOf(Integer code) {
        if (!isTaglessCode(code)) {
            return null;
        }
        return code == 1;
    }

    private static boolean isTaglessCode(Integer code) {
        return code != null && (code == 0 || code == 1);
    }

    private static int stopOrderOf(Integer order) {
        if (order == null) {
            return 0;
        }
        return order;
    }

    private static Integer nonnegative(JsonNode node, String field) {
        Integer value = number(node, field);
        if (value == null || value < 0) {
            return null;
        }
        return value;
    }

    private static Duration retryAfter(String value, Instant receivedAt) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            Duration delay = retryDelayOf(value, receivedAt);
            delay.toNanos(); // Worker의 단조 시계로 표현할 수 없는 힌트는 무시한다.
            if (delay.isNegative()) {
                return Duration.ZERO;
            }
            return delay;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static Duration retryDelayOf(String value, Instant receivedAt) {
        if (value.strip().matches("[0-9]+")) {
            return Duration.ofSeconds(Long.parseLong(value.strip()));
        }
        return Duration.between(receivedAt,
            ZonedDateTime.parse(value.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
    }

    private static Integer number(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        }
        catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("숫자 필드 미해석");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText().strip();
        if (text.isEmpty()) {
            return null;
        }
        return text;
    }
}
