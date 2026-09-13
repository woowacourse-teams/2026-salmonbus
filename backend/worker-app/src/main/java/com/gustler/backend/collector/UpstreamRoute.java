package com.gustler.backend.collector;

/**
 * 상류가 알려준 노선 한 건. 노선 행에 넣을 이름들과 판본을 여는 데 필요한 것이 다 들어 있다.
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
