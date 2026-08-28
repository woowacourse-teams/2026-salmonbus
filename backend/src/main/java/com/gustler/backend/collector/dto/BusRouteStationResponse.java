package com.gustler.backend.collector.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 노선이 지나는 정류소 목록. 회차하는 노선은 같은 정류소가 두 번 나오고 순번이 그것을 가른다.
 *
 * <p>필드 이름은 공공데이터포털 문서를 보고 맞춘 것이다. 서비스키가 없어 실제 응답으로 대조하지 못했다.
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
        Integer stopOrder
    ) {
    }
}
