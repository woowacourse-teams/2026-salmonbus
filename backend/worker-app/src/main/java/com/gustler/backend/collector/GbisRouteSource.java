package com.gustler.backend.collector;

import com.gustler.backend.collector.GbisRawResponse.NotReceived;
import com.gustler.backend.collector.GbisRawResponse.PortalRejected;
import com.gustler.backend.collector.GbisRawResponse.Received;
import com.gustler.backend.collector.GbisRouteResult.Failed;
import com.gustler.backend.collector.GbisRouteResult.Success;
import com.gustler.backend.collector.dto.BusRouteInfoResponse;
import com.gustler.backend.collector.dto.BusRouteInfoResponse.RouteInfoItem;
import com.gustler.backend.collector.dto.BusRouteStationResponse;
import com.gustler.backend.collector.dto.BusRouteStationResponse.RouteStationItem;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 노선 하나의 기본 정보와 경유 정류소를 상류에서 읽는다.
 *
 * <p>상류가 둘을 따로 준다. 표시명 · 기점 · 종점 · 첫차 · 막차 · 회차 순번은 노선정보에 있고,
 * 정류소 목록은 노선정류소 조회에 있다. 그래서 한 노선을 읽는 데 호출을 두 번 쓴다.
 */
@Component
public class GbisRouteSource {

    /** 한 노선을 읽는 데 드는 상류 호출 수. 장부에서 이만큼 자리를 잡고 부른다. */
    public static final int UPSTREAM_CALLS_PER_READ = 2;

    private static final String ROUTE_INFO_PATH = "/busrouteservice/v2/getBusRouteInfoItemv2";
    private static final String ROUTE_STATION_PATH = "/busrouteservice/v2/getBusRouteStationListv2";

    private static final int RESULT_CODE_SUCCESS = 0;

    private final GbisApiCaller caller;
    private final ObjectMapper objectMapper;

    public GbisRouteSource(
        GbisApiCaller caller,
        ObjectMapper objectMapper
    ) {
        this.caller = caller;
        this.objectMapper = objectMapper;
    }

    public GbisRouteResult read(
        final String routeId
    ) {
        RouteInfoItem routeInfo = readRouteInfo(routeId);
        if (routeInfo == null) {
            return new Failed("노선 %s 의 기본 정보를 읽지 못했다".formatted(routeId));
        }

        List<RouteStationItem> stations = readStations(routeId);
        if (stations == null || stations.isEmpty()) {
            return new Failed("노선 %s 의 경유 정류소를 읽지 못했다".formatted(routeId));
        }

        return toSuccess(routeId, routeInfo, stations);
    }

    private GbisRouteResult toSuccess(
        String routeId,
        RouteInfoItem routeInfo,
        List<RouteStationItem> stations
    ) {
        try {
            return new Success(new UpstreamRoute(
                routeId,
                routeInfo.displayName(),
                routeInfo.startStopName(),
                routeInfo.endStopName(),
                RouteStops.from(routeInfo.turnSequence(), toUpstreamStops(stations)),
                timetableOf(routeInfo)));
        } catch (final IllegalArgumentException e) {
            // 순번이 겹치거나 회차 순번이 정류소 목록에 없는 응답. 판본으로 열면 뜻이 없는 노선이 된다.
            return new Failed("노선 %s 의 정류소 목록이 성립하지 않는다: %s".formatted(routeId, e.getMessage()));
        }
    }

    private List<UpstreamRouteStop> toUpstreamStops(
        List<RouteStationItem> stations
    ) {
        return stations.stream()
            .map(station -> new UpstreamRouteStop(station.stopOrder(), station.stopId(), station.name()))
            .toList();
    }

    private RouteTimetable timetableOf(
        RouteInfoItem routeInfo
    ) {
        return new RouteTimetable(
            routeInfo.upFirstDepartureTime(),
            routeInfo.upLastDepartureTime(),
            routeInfo.downFirstDepartureTime(),
            routeInfo.downLastDepartureTime());
    }

    private RouteInfoItem readRouteInfo(
        String routeId
    ) {
        BusRouteInfoResponse response = read(ROUTE_INFO_PATH, routeId, BusRouteInfoResponse.class);
        if (response == null
            || response.response() == null
            || response.response().header() == null
            || response.response().header().resultCode() != RESULT_CODE_SUCCESS
            || response.response().body() == null) {
            return null;
        }
        return response.response().body().routeInfo();
    }

    private List<RouteStationItem> readStations(
        String routeId
    ) {
        BusRouteStationResponse response = read(ROUTE_STATION_PATH, routeId, BusRouteStationResponse.class);
        if (response == null
            || response.response() == null
            || response.response().header() == null
            || response.response().header().resultCode() != RESULT_CODE_SUCCESS
            || response.response().body() == null) {
            return null;
        }
        return response.response().body().stations();
    }

    /** 응답이 안 왔거나 포털이 막았거나 읽지 못하면 비운다. 부른 쪽이 그 노선을 건너뛴다. */
    private <T> T read(
        String path,
        String routeId,
        Class<T> responseType
    ) {
        return switch (caller.get(path, routeId)) {
            case NotReceived ignored -> null;
            case PortalRejected ignored -> null;
            case Received received -> parse(received.body(), responseType);
        };
    }

    private <T> T parse(
        String body,
        Class<T> responseType
    ) {
        try {
            return objectMapper.readValue(body, responseType);
        } catch (final JacksonException e) {
            return null;
        }
    }
}
