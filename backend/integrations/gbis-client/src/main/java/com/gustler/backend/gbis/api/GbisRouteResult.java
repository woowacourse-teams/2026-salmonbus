package com.gustler.backend.gbis.api;

import com.gustler.backend.gbis.api.dto.BusRouteInfoResponse.RouteInfoItem;
import com.gustler.backend.gbis.api.dto.BusRouteStationResponse.RouteStationItem;
import java.util.List;

/**
 * 노선 조회의 상류 응답을 읽은 결과.
 *
 * <p>상류가 노선 기본 정보와 경유 정류소를 따로 주므로 둘 중 어느 쪽을 못 읽었는지 구분한다.
 * 읽은 값이 업무에 쓸 만한지는 부른 쪽이 판단한다.
 */
public sealed interface GbisRouteResult {

    record Success(
        RouteInfoItem routeInfo,
        List<RouteStationItem> stations
    ) implements GbisRouteResult {
    }

    /** 노선 기본 정보를 못 읽었다. 응답이 안 왔거나 포털이 막았거나 읽지 못했다. */
    record RouteInfoUnavailable() implements GbisRouteResult {
    }

    /** 경유 정류소 목록을 못 읽었다. */
    record StationsUnavailable() implements GbisRouteResult {
    }
}
