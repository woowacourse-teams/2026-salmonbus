package com.gustler.backend.collector.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 노선이 지나는 정류소 목록. 회차하는 노선은 같은 정류소가 두 번 나오고 순번이 그것을 가른다.
 *
 * <p>회차 순번이 여기 있다. 노선정보 응답에는 없다. 정류소마다 같은 {@code turnSeq} 가 실려 오고
 * 회차하는 정류소만 {@code turnYn} 이 Y 다. 2026-09-02 실제 응답으로 대조했다.
 * 3330 은 43번 안양역, 1650 은 44번 안양역이다.
 */
public record BusRouteStationResponse(
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
        /** 정류소가 하나뿐이면 상류가 배열이 아니라 객체 하나로 준다. 위치정보 응답과 같은 버릇이다. */
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
        @JsonProperty("busRouteStationList")
        List<RouteStationItem> stations
    ) {
    }

    public record RouteStationItem(
        @JsonProperty("stationId")
        String stopId,

        @JsonProperty("stationName")
        String name,

        @JsonProperty("stationSeq")
        Integer stopOrder,

        /** 이 노선의 회차 정류소 순번. 모든 정류소 행에 같은 값이 실린다. 단방향이면 비어 있다. */
        @JsonProperty("turnSeq")
        Integer turnSequence,

        /** 이 정류소가 회차 지점인가. 회차하는 노선은 한 정류소만 {@code Y} 다. */
        @JsonProperty("turnYn")
        String turnPoint
    ) {

        private static final String TURN_POINT = "Y";

        public boolean isTurnPoint() {
            return TURN_POINT.equals(turnPoint);
        }
    }
}
