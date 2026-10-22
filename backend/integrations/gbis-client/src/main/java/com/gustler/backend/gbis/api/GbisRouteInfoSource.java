package com.gustler.backend.gbis.api;

import com.gustler.backend.gbis.api.GbisRawResponse.NotReceived;
import com.gustler.backend.gbis.api.GbisRawResponse.PortalRejected;
import com.gustler.backend.gbis.api.GbisRawResponse.Received;
import com.gustler.backend.gbis.api.GbisRouteResult.RouteInfoUnavailable;
import com.gustler.backend.gbis.api.GbisRouteResult.StationsUnavailable;
import com.gustler.backend.gbis.api.GbisRouteResult.Success;
import com.gustler.backend.gbis.api.dto.BusRouteInfoResponse;
import com.gustler.backend.gbis.api.dto.BusRouteInfoResponse.RouteInfoItem;
import com.gustler.backend.gbis.api.dto.BusRouteStationResponse;
import com.gustler.backend.gbis.api.dto.BusRouteStationResponse.RouteStationItem;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 노선 하나의 기본 정보와 경유 정류소를 상류에서 읽는다.
 *
 * <p>상류가 둘을 따로 준다. 표시명 · 기점 · 종점 · 첫차 · 막차는 노선정보에 있고, 정류소 목록과
 * <b>회차 순번</b>은 노선정류소 조회에 있다. 그래서 한 노선을 읽는 데 호출을 두 번 쓴다.
 *
 * <p>여기까지가 통신과 응답 해석이다. 읽은 값을 업무 의미로 바꾸는 일은 부른 쪽 어댑터가 맡는다.
 */
@Component
public class GbisRouteInfoSource {
    /** 한 노선을 읽는 데 드는 상류 호출 수. 장부에서 이 호출 수를 예약한 뒤 호출한다. */
    public static final int UPSTREAM_CALLS_PER_READ = 2;

    private static final String ROUTE_INFO_PATH = "/busrouteservice/v2/getBusRouteInfoItemv2";
    private static final String ROUTE_STATION_PATH = "/busrouteservice/v2/getBusRouteStationListv2";

    private final GbisApiCaller caller;
    private final ObjectMapper objectMapper;

    public GbisRouteInfoSource(
        GbisApiCaller caller,
        ObjectMapper objectMapper
    ) {
        this.caller = caller;
        this.objectMapper = objectMapper;
    }

    /**
     * GBIS 노선 정보를 호출해 원천 필드를 반환한다. 알 수 없는 값과 조회 실패를 임의의 정상 기본값으로 채우지 않는다.
     */
    public GbisRouteResult read(
        String routeId
    ) {
        RouteInfoItem routeInfo = readRouteInfo(routeId);
        if (routeInfo == null) {
            return new RouteInfoUnavailable();
        }
        List<RouteStationItem> stations = readStations(routeId);
        if (stations == null) {
            return new StationsUnavailable();
        }
        return new Success(routeInfo, stations);
    }

    private RouteInfoItem readRouteInfo(
        String routeId
    ) {
        BusRouteInfoResponse response = read(ROUTE_INFO_PATH, routeId, BusRouteInfoResponse.class);
        if (!isSuccessful(response)) {
            return null;
        }
        BusRouteInfoResponse.Response providerResponse = response.response();
        return providerResponse.body().routeInfo();
    }

    private boolean isSuccessful(BusRouteInfoResponse response) {
        if (response == null || response.response() == null) {
            return false;
        }
        BusRouteInfoResponse.Response providerResponse = response.response();
        if (providerResponse.header() == null || providerResponse.body() == null) {
            return false;
        }
        BusRouteInfoResponse.Header header = providerResponse.header();
        return GbisResultCode.from(header.resultCode()) == GbisResultCode.SUCCESS;
    }

    private List<RouteStationItem> readStations(
        String routeId
    ) {
        BusRouteStationResponse response = read(ROUTE_STATION_PATH, routeId, BusRouteStationResponse.class);
        if (!isSuccessful(response)) {
            return null;
        }
        BusRouteStationResponse.Response providerResponse = response.response();
        return providerResponse.body().stations();
    }

    private boolean isSuccessful(BusRouteStationResponse response) {
        if (response == null || response.response() == null) {
            return false;
        }
        BusRouteStationResponse.Response providerResponse = response.response();
        if (providerResponse.header() == null || providerResponse.body() == null) {
            return false;
        }
        BusRouteStationResponse.Header header = providerResponse.header();
        return GbisResultCode.from(header.resultCode()) == GbisResultCode.SUCCESS;
    }

    /** 응답이 오지 않거나 포털이 거부하거나 읽지 못하면 비운다. 호출한 쪽은 해당 노선을 건너뛴다. */
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
        } catch (JacksonException e) {
            return null;
        }
    }
}
