package com.gustler.backend.collector.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

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
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
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
