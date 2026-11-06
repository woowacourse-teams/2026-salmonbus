package com.gustler.backend.api.station.domain;

/**
 * 정류장 상세 머리글의 이름·좌표·지역·방면. 우리 노선 정류장이면 노선 자료를 쓴다.
 * direction은 다음 정차 정류장 이름이며 우리 노선이 없는 정류장은 null이다.
 */
public record StationInfo(
    String stationId,
    String name,
    double x,
    double y,
    String mobileNo,
    String regionName,
    String direction
) {
}
