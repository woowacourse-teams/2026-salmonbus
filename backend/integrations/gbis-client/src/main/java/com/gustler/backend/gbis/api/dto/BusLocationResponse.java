package com.gustler.backend.gbis.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonFormat.Feature;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.gustler.backend.gbis.api.dto.BusLocationResponse.Response;
import java.util.List;

/**
 * GBIS 위치정보 JSON 봉투다.
 * 누락·null·목록 원소와 수정 가능성은 역직렬화 결과대로 보존하며 업무 검증은 어댑터가 맡는다.
 */
public record BusLocationResponse(
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
        @JsonFormat(with = Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
        @JsonProperty("busLocationList")
        List<BusLocation> busLocations
    ) {
    }

    public record BusLocation(
        @JsonProperty("plateNo")
        String plateNumber,

        @JsonProperty("vehId")
        String vehicleId,

        @JsonProperty("lowPlate")
        Integer vehicleType,

        @JsonProperty("routeId")
        String routeId,

        @JsonProperty("routeTypeCd")
        Integer routeType,

        @JsonProperty("stationId")
        String stopId,

        @JsonProperty("stationSeq")
        Integer stopSequence,

        @JsonProperty("stateCd")
        Integer runningStatus,

        @JsonProperty("remainSeatCnt")
        Integer remainingSeatCount,

        @JsonProperty("crowded")
        Integer crowdLevel,

        @JsonProperty("taglessCd")
        Integer taglessCode
    ) {
    }
}
