package com.gustler.backend.collector.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PortalErrorResponse(
    @JsonProperty("OpenAPI_ServiceResponse")
    ServiceResponse response
) {

    public record ServiceResponse(
        @JsonProperty("cmmMsgHeader")
        CommonHeader header
    ) {
    }

    public record CommonHeader(
        String errMsg,
        String returnAuthMsg,
        String returnReasonCode
    ) {
    }
}
