package com.gustler.backend.collector.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 노선 한 건의 기본 정보. 표시명과 기점 · 종점 이름과 첫차 · 막차 시각과 회차 순번이 여기서 온다.
 *
 * <p>회차 순번은 여기 없다. 이 응답에는 회차 정류소의 ID 와 이름(turnStID · turnStNm)만 있고
 * 순번은 정류소 목록 응답이 준다. 2026-09-02 실제 응답으로 대조했다.
 * 이름이 어긋나면 값이 null 로 들어오고 읽지 못한 응답으로 떨어진다.
 */
public record BusRouteInfoResponse(
    Response response
) {

    public record Response(
        @JsonProperty("msgHeader")
        Header header,

        @JsonProperty("msgBody")
        Body body
    ) {
    }

    public record Header(
        int resultCode,
        String resultMessage,
        String queryTime
    ) {
    }

    public record Body(
        @JsonProperty("busRouteInfoItem")
        RouteInfoItem routeInfo
    ) {
    }

    public record RouteInfoItem(
        @JsonProperty("routeName")
        String displayName,

        @JsonProperty("startStationName")
        String startStopName,

        @JsonProperty("endStationName")
        String endStopName,

        @JsonProperty("upFirstTime")
        String upFirstDepartureTime,

        @JsonProperty("upLastTime")
        String upLastDepartureTime,

        @JsonProperty("downFirstTime")
        String downFirstDepartureTime,

        @JsonProperty("downLastTime")
        String downLastDepartureTime
    ) {
    }
}
