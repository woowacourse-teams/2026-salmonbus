package com.gustler.backend.routecatalog.domain;

/**
 * 상류에서 받은 노선 한 건. 노선 행에 저장할 이름과 버전 생성에 필요한 정보를 담는다.
 */
public record UpstreamRoute(
    String upstreamRouteId,
    String displayName,
    String startStopName,
    String endStopName,
    RouteStops stops,
    RouteTimetable timetable
) {
}
