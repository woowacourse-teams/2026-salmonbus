package com.gustler.backend.collector;

import com.gustler.backend.collector.GbisLocationResult.DailyQuotaExceeded;
import com.gustler.backend.collector.GbisLocationResult.GatewayRejected;
import com.gustler.backend.collector.GbisLocationResult.GbisSystemError;
import com.gustler.backend.collector.GbisLocationResult.MissingRequiredParameter;
import com.gustler.backend.collector.GbisLocationResult.NoResponse;
import com.gustler.backend.collector.GbisLocationResult.NoVehicles;
import com.gustler.backend.collector.GbisLocationResult.PerSecondQuotaExceeded;
import com.gustler.backend.collector.GbisLocationResult.Success;
import com.gustler.backend.collector.GbisLocationResult.UnknownGbisResultCode;
import com.gustler.backend.collector.GbisLocationResult.UnreadableResponse;
import com.gustler.backend.collector.dto.BusLocationResponse;
import com.gustler.backend.collector.dto.BusLocationResponse.Body;
import com.gustler.backend.collector.dto.BusLocationResponse.Header;
import com.gustler.backend.collector.dto.PortalErrorResponse;
import com.gustler.backend.collector.dto.PortalErrorResponse.CommonHeader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.ResponseSpec.ErrorHandler;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class GbisLocationSource {

    private static final String BUS_LOCATION_PATH = "/buslocationservice/v2/getBusLocationListv2";

    private static final String PORTAL_ERROR_ROOT = "OpenAPI_ServiceResponse";
    private static final Pattern REASON_CODE_IN_XML = Pattern.compile("<returnReasonCode>(.*?)</returnReasonCode>");
    private static final Pattern ERROR_CODE_IN_XML = Pattern.compile("<errMsg>(.*?)</errMsg>");
    private static final Pattern AUTH_MESSAGE_IN_XML = Pattern.compile("<returnAuthMsg>(.*?)</returnAuthMsg>");
    private static final String UNKNOWN_REASON_CODE = "UNKNOWN";
    private static final String NO_MESSAGE = "";
    private static final String PARSE_FAILURE = "Open API 응답을 파싱하지 못했다: ";
    private static final String NO_HEADER = "Open API 응답에 헤더가 없다";

    private static final ErrorHandler KEEP_ERROR_BODY = (request, response) -> {
    };

    private final RestClient gbisRestClient;
    private final GbisProperties properties;
    private final ObjectMapper objectMapper;

    public GbisLocationSource(
        RestClient gbisRestClient,
        GbisProperties properties,
        ObjectMapper objectMapper
    ) {
        this.gbisRestClient = gbisRestClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    // TODO 로깅 혹은 저장 시 serviceKey 를 마스킹해야 한다.
    public GbisLocationResult read(
        String routeId
    ) {
        try {
            byte[] raw = gbisRestClient.get()
                .uri(builder -> builder
                    .path(BUS_LOCATION_PATH)
                    .queryParam("serviceKey", properties.serviceKey())
                    .queryParam("routeId", routeId)
                    .queryParam("format", "json")
                    .build())
                .retrieve()
                .onStatus(HttpStatusCode::isError, KEEP_ERROR_BODY)
                .body(byte[].class);
            return interpret(decode(raw));
        } catch (RestClientException e) {
            return new NoResponse(e.getMessage());
        }
    }

    private String decode(
        byte[] raw
    ) {
        if (raw == null) {
            return null;
        }
        return new String(raw, StandardCharsets.UTF_8);
    }

    private GbisLocationResult interpret(
        String body
    ) {
        if (body == null || body.isBlank()) {
            return new NoResponse("Open API가 본문 없이 응답했다");
        }
        if (body.contains(PORTAL_ERROR_ROOT)) {
            return interpretPortalError(body);
        }
        return interpretGbisResponse(body);
    }

    private GbisLocationResult interpretPortalError(
        String body
    ) {
        CommonHeader header = parsePortalHeaderOf(body);

        return switch (PortalReasonCode.from(header.returnReasonCode())) {
            case DAILY_QUOTA_EXCEEDED -> new DailyQuotaExceeded();
            case PER_SECOND_QUOTA_EXCEEDED -> new PerSecondQuotaExceeded();
            case OTHER -> new GatewayRejected(header.returnReasonCode(), header.errMsg(), header.returnAuthMsg());
        };
    }

    private CommonHeader parsePortalHeaderOf(
        String body
    ) {
        return parsePortalError(body)
            .orElseGet(() -> portalHeaderFromXml(body));
    }

    private Optional<CommonHeader> parsePortalError(
        String body
    ) {
        try {
            PortalErrorResponse parsed = objectMapper.readValue(body, PortalErrorResponse.class);
            return Optional.ofNullable(parsed.response())
                .map(PortalErrorResponse.ServiceResponse::header);
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }

    private CommonHeader portalHeaderFromXml(
        String body
    ) {
        return new CommonHeader(
            extract(ERROR_CODE_IN_XML, body).orElse(NO_MESSAGE),
            extract(AUTH_MESSAGE_IN_XML, body).orElse(NO_MESSAGE),
            extract(REASON_CODE_IN_XML, body).orElse(UNKNOWN_REASON_CODE));
    }

    private Optional<String> extract(
        Pattern pattern,
        String body
    ) {
        Matcher matcher = pattern.matcher(body);
        if (matcher.find()) {
            return Optional.of(matcher.group(1).trim());
        }
        return Optional.empty();
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
