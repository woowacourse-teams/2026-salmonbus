package com.gustler.backend.api.station.domain;

import java.util.List;

/**
 * 지도 칸에 찍을 정류장 한 곳. 우리 노선 정류장은 노선 자료의 이름·좌표를 쓴다.
 * salmonRouteIds가 비어 있으면 예보 노선이 없는 정류장이다. direction은 다음 정차 정류장 이름이며 우리 노선 정류장만 있다.
 */
public record MapStation(
    String stationId,
    String name,
    double x,
    double y,
    String mobileNo,
    String regionName,
    String direction,
    List<String> salmonRouteIds
) {

    public MapStation {
        salmonRouteIds = List.copyOf(salmonRouteIds);
    }
}
