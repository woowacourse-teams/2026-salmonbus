package com.gustler.backend.stations.infrastructure.gbis;

import com.gustler.backend.gbis.api.GbisApiCaller;
import com.gustler.backend.gbis.api.GbisHttpResult;
import com.gustler.backend.gbis.api.GbisRawResponse;
import com.gustler.backend.gbis.api.PortalReasonCode;
import com.gustler.backend.stations.domain.BusStation;
import com.gustler.backend.stations.domain.StationAroundSource;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 주변정류소 응답을 해석한다. HTTP 성공과 정상 빈 결과, 본문 누락을 구분한다.
 *
 * <p>헤더 없는 본문과 잔여량 0은 일시 차단 신호이며 잔여량을 알 수 없으면 재확인이 필요한 응답으로 둔다.
 */
public class GbisStationAroundSource implements StationAroundSource {

    public static final String PATH = "/busstationservice/v2/getBusStationAroundListv2";
    private static final int NO_HTTP_RESPONSE = 0;
    private final GbisApiCaller caller;
    private final ObjectMapper json;

    public GbisStationAroundSource(GbisApiCaller caller, ObjectMapper json) {
        this.caller = caller;
        this.json = json;
    }

    @Override
    public StationAroundSource.Result around(final double longitude, final double latitude, String keyAlias) {
        GbisHttpResult http = caller.getByCoordinateWithMetadata(PATH, longitude, latitude, keyAlias);
        Optional<StationAroundSource.Result> failure = transportFailureOf(http);
        if (failure.isPresent()) {
            return failure.get();
        }
        GbisRawResponse.Received received = (GbisRawResponse.Received) http.response();
        return decode(received.body(), http.rateLimitRemaining());
    }

    private static Optional<StationAroundSource.Result> transportFailureOf(GbisHttpResult http) {
        if (http.response() instanceof GbisRawResponse.PortalRejected rejected) {
            return Optional.of(rejectedOf(rejected));
        }
        if (http.httpStatus() == HttpStatus.TOO_MANY_REQUESTS.value()) {
            return Optional.of(new StationAroundSource.Throttled());
        }
        if (hasRejectedKey(http)) {
            return Optional.of(new StationAroundSource.Exhausted("KEY_REJECTED"));
        }
        if (hasTransportFailure(http)) {
            return Optional.of(new StationAroundSource.Failed("UPSTREAM_ERROR", retryAfterOf(http.retryAfter())));
        }
        if (!(http.response() instanceof GbisRawResponse.Received)) {
            return Optional.of(new StationAroundSource.Failed("UNREADABLE_RESPONSE", null));
        }
        return Optional.empty();
    }

    private static boolean hasRejectedKey(GbisHttpResult http) {
        return http.httpStatus() == HttpStatus.UNAUTHORIZED.value()
            || http.httpStatus() == HttpStatus.FORBIDDEN.value();
    }

    private static boolean hasTransportFailure(GbisHttpResult http) {
        if (http.response() instanceof GbisRawResponse.NotReceived) {
            return true;
        }
        return http.httpStatus() >= HttpStatus.BAD_REQUEST.value() || http.httpStatus() == NO_HTTP_RESPONSE;
    }

    private StationAroundSource.Result decode(String body, Integer remaining) {
        try {
            JsonNode response = json.readTree(body).path("response");
            JsonNode header = response.path("msgHeader");
            if (!header.isObject()) {
                return missingHeaderOf(remaining);
            }
            return decodedResultOf(response, header.path("resultCode").asInt(-1), remaining);
        } catch (RuntimeException unreadable) {
            return new StationAroundSource.Failed("UNREADABLE_RESPONSE", null);
        }
    }

    private static StationAroundSource.Result missingHeaderOf(Integer remaining) {
        if (remaining != null && remaining == 0) {
            return new StationAroundSource.Throttled();
        }
        return new StationAroundSource.Ambiguous(remaining);
    }

    private static StationAroundSource.Result decodedResultOf(JsonNode response, final int code, Integer remaining) {
        if (code == 4) {
            return new StationAroundSource.NoStations(remaining);
        }
        if (code != 0) {
            return new StationAroundSource.Failed("UPSTREAM_RESULT_" + code, null);
        }
        JsonNode rows = response.path("msgBody").path("busStationAroundList");
        if (hasMissingRows(rows)) {
            return new StationAroundSource.Failed("EMPTY_SUCCESS_BODY", null);
        }
        List<JsonNode> rawRows = rowsOf(rows);
        List<BusStation> stations = stationsOf(rawRows);
        if (rawRows.stream().anyMatch(GbisStationAroundSource::isInvalidBoardingRow)) {
            return new StationAroundSource.Failed("INVALID_STATION_ROW", null);
        }
        return new StationAroundSource.Found(stations, remaining);
    }

    private static boolean hasMissingRows(JsonNode rows) {
        final boolean hasRowContainer = rows.isArray() || rows.isObject();
        return !hasRowContainer || rows.isEmpty();
    }

    private static List<JsonNode> rowsOf(JsonNode rows) {
        if (rows.isArray()) {
            return StreamSupport.stream(rows.spliterator(), false).toList();
        }
        if (rows.isObject()) {
            return List.of(rows);
        }
        return List.of();
    }

    private static List<BusStation> stationsOf(List<JsonNode> rows) {
        return rows.stream()
            .map(GbisStationAroundSource::stationOf)
            .flatMap(Optional::stream)
            .toList();
    }

    private static boolean isInvalidBoardingRow(JsonNode row) {
        String stationId = textOf(row, "stationId");
        if (stationId != null && stationId.matches("277[0-9]{6}")) {
            return false;
        }
        return stationOf(row).isEmpty();
    }

    private static StationAroundSource.Result rejectedOf(GbisRawResponse.PortalRejected rejected) {
        if (rejected.reason() == PortalReasonCode.PER_SECOND_QUOTA_EXCEEDED) {
            return new StationAroundSource.Throttled();
        }
        if (rejected.reason() == PortalReasonCode.DAILY_QUOTA_EXCEEDED) {
            return new StationAroundSource.Exhausted("DAILY_QUOTA_EXCEEDED");
        }
        return new StationAroundSource.Exhausted("KEY_REJECTED");
    }

    private static Optional<BusStation> stationOf(JsonNode row) {
        return BusStation.from(
            textOf(row, "stationId"),
            textOf(row, "stationName"),
            numberOf(row, "x"),
            numberOf(row, "y"),
            textOf(row, "mobileNo"),
            textOf(row, "regionName"),
            textOf(row, "centerYn")
        );
    }

    private static String textOf(JsonNode row, String field) {
        JsonNode value = row.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    private static Double numberOf(JsonNode row, String field) {
        JsonNode value = row.path(field);
        if (!value.isNumber()) {
            return null;
        }
        return value.asDouble();
    }

    private static Duration retryAfterOf(String header) {
        if (header == null || !header.matches("[0-9]{1,5}")) {
            return null;
        }
        return Duration.ofSeconds(Long.parseLong(header));
    }
}
