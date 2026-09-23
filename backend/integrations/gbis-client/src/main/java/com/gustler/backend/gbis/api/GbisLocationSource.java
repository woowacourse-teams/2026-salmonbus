package com.gustler.backend.gbis.api;

import com.gustler.backend.gbis.api.GbisLocationResult.DailyQuotaExceeded;
import com.gustler.backend.gbis.api.GbisLocationResult.GatewayRejected;
import com.gustler.backend.gbis.api.GbisLocationResult.GbisSystemError;
import com.gustler.backend.gbis.api.GbisLocationResult.MissingRequiredParameter;
import com.gustler.backend.gbis.api.GbisLocationResult.NoResponse;
import com.gustler.backend.gbis.api.GbisLocationResult.NoVehicles;
import com.gustler.backend.gbis.api.GbisLocationResult.PerSecondQuotaExceeded;
import com.gustler.backend.gbis.api.GbisLocationResult.Success;
import com.gustler.backend.gbis.api.GbisLocationResult.UnknownGbisResultCode;
import com.gustler.backend.gbis.api.GbisLocationResult.UnreadableResponse;
import com.gustler.backend.gbis.api.GbisRawResponse.NotReceived;
import com.gustler.backend.gbis.api.GbisRawResponse.PortalRejected;
import com.gustler.backend.gbis.api.GbisRawResponse.Received;
import com.gustler.backend.gbis.api.dto.BusLocationResponse;
import com.gustler.backend.gbis.api.dto.BusLocationResponse.Body;
import com.gustler.backend.gbis.api.dto.BusLocationResponse.Header;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class GbisLocationSource {

    private static final String BUS_LOCATION_PATH = "/buslocationservice/v2/getBusLocationListv2";

    private static final String PARSE_FAILURE = "Open API 응답을 파싱하지 못했다: ";
    private static final String NO_HEADER = "Open API 응답에 헤더가 없다";

    private final GbisApiCaller caller;
    private final ObjectMapper objectMapper;

    public GbisLocationSource(
        GbisApiCaller caller,
        ObjectMapper objectMapper
    ) {
        this.caller = caller;
        this.objectMapper = objectMapper;
    }

    public GbisLocationResult read(
        String routeId
    ) {
        return switch (caller.get(BUS_LOCATION_PATH, routeId)) {
            case NotReceived notReceived -> new NoResponse(notReceived.message());
            case PortalRejected rejected -> portalResultOf(rejected);
            case Received received -> interpretGbisResponse(received.body());
        };
    }

    private GbisLocationResult portalResultOf(
        PortalRejected rejected
    ) {
        return switch (rejected.reason()) {
            case DAILY_QUOTA_EXCEEDED -> new DailyQuotaExceeded();
            case PER_SECOND_QUOTA_EXCEEDED -> new PerSecondQuotaExceeded();
            case OTHER -> new GatewayRejected(rejected.reasonCode(), rejected.errorCode(), rejected.message());
        };
    }

    private GbisLocationResult interpretGbisResponse(
        String body
    ) {
        BusLocationResponse response;
        try {
            response = objectMapper.readValue(body, BusLocationResponse.class);
        } catch (JacksonException e) {
            return new UnreadableResponse(PARSE_FAILURE + e.getOriginalMessage());
        }

        if (hasHeader(response)) {
            return interpretHeader(response);
        }
        return new UnreadableResponse(NO_HEADER);
    }

    private boolean hasHeader(
        BusLocationResponse response
    ) {
        return response.response() != null && response.response().header() != null;
    }

    private GbisLocationResult interpretHeader(
        BusLocationResponse response
    ) {
        Header header = response.response().header();
        final int resultCode = header.resultCode();

        return switch (GbisResultCode.from(resultCode)) {
            case SUCCESS -> interpretSuccess(header, response);
            case NO_VEHICLES -> new NoVehicles(header.queryTime());
            case SYSTEM_FAILURE -> new GbisSystemError(header.queryTime(), header.resultMessage());
            case PARAMETER_MISSING -> new MissingRequiredParameter(header.queryTime(), header.resultMessage());
            case OTHER -> new UnknownGbisResultCode(header.queryTime(), resultCode, header.resultMessage());
        };
    }

    private GbisLocationResult interpretSuccess(
        Header header,
        BusLocationResponse response
    ) {
        Body body = response.response().body();

        if (body == null || body.busLocations() == null) {
            return new UnreadableResponse("결과 코드는 정상인데 응답 본문이 없다");
        }
        return new Success(header.queryTime(), body.busLocations());
    }
}
