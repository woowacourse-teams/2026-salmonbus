package com.gustler.backend.collector.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 노선 한 건의 기본 정보. 표시명과 기점 · 종점 이름과 첫차 · 막차 시각과 회차 순번이 여기서 온다.
 *
 * <p>필드 이름은 공공데이터포털 문서를 보고 맞춘 것이다. 서비스키가 없어 실제 응답으로 대조하지 못했다.
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
        String downLastDepartureTime,

        /** 회차 지점의 순번. 단방향 노선은 비어 있다. */
        @JsonProperty("turnSeq")
        Integer turnSequence
    ) {
    }
}
