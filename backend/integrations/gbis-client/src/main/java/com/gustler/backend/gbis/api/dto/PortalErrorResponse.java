package com.gustler.backend.gbis.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gustler.backend.gbis.api.dto.PortalErrorResponse.ServiceResponse;

/**
 * GBIS 응답 앞에서 발생한 공공데이터포털 오류의 JSON 봉투와 원문 코드다.
 */
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
