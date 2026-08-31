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

        /** 회차 지점의 순번. 단방향 노선은 비어 있거나 0 으로 온다. */
        @JsonProperty("turnSeq")
        Integer turnSequence
    ) {

        private static final int NO_TURN_POINT = 0;

        /**
         * 0 을 회차 없음으로 접는다. 정류소 순번은 1 부터라 0 인 회차 지점은 있을 수 없다.
         *
         * <p>접지 않으면 RouteStops 가 "회차 순번 0 인 정류소를 경유하지 않는다" 로 거절하고,
         * 그 노선은 판본이 안 열려서 수집이 통째로 멈춘다. 상류가 0 을 주는지 실제로 보진 못했는데,
         * 단방향을 0 으로 알리는 API 가 흔해서 미리 접어둔다.
         */
        public RouteInfoItem {
            if (turnSequence != null && turnSequence == NO_TURN_POINT) {
                turnSequence = null;
            }
        }
    }
}
