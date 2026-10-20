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

/**
 * 위치정보 HTTP 응답과 포털 거부 사유를 수집 결과로 해석한다.
 * 응답 봉투의 구조는 이 경계에서만 다룬다.
 */
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

    /**
     * GBIS 차량 위치를 호출하고 정상·키 거부·원천 실패를 구별한다. 원천 응답 형식을 업무 관측으로 직접 저장하지 않는다.
     */
    public GbisLocationResult read(
        String routeId
    ) {
        return read(routeId, GbisKey.PRIMARY);
    }

    /**
     * GBIS 차량 위치를 호출하고 정상·키 거부·원천 실패를 구별한다. 원천 응답 형식을 업무 관측으로 직접 저장하지 않는다.
     */
    public GbisLocationResult read(
        String routeId,
        String keyAlias
    ) {
        return switch (caller.get(BUS_LOCATION_PATH, routeId, keyAlias)) {
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
            case UNREGISTERED_KEY, EXPIRED_KEY, OTHER ->
            new GatewayRejected(rejected.reasonCode(), rejected.errorCode(), rejected.message());
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
