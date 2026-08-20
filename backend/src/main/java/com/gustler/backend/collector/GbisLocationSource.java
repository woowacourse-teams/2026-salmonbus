package com.gustler.backend.collector;

import com.gustler.backend.collector.GbisLocationResult.DailyQuotaExceeded;
import com.gustler.backend.collector.GbisLocationResult.GatewayRejected;
import com.gustler.backend.collector.GbisLocationResult.GbisSystemError;
import com.gustler.backend.collector.GbisLocationResult.MissingRequiredParameter;
import com.gustler.backend.collector.GbisLocationResult.NoResponse;
import com.gustler.backend.collector.GbisLocationResult.NoVehicles;
import com.gustler.backend.collector.GbisLocationResult.PerSecondQuotaExceeded;
import com.gustler.backend.collector.GbisLocationResult.Success;
import com.gustler.backend.collector.dto.BusLocationResponse;
import com.gustler.backend.collector.dto.BusLocationResponse.BusLocation;
import com.gustler.backend.collector.dto.BusLocationResponse.Header;
import java.util.List;
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

    private static final String XML_PREFIX = "<";
    private static final Pattern REASON_CODE_IN_XML = Pattern.compile("<returnReasonCode>(.*?)</returnReasonCode>");
    private static final Pattern ERROR_MESSAGE_IN_XML = Pattern.compile("<errMsg>(.*?)</errMsg>");
    private static final String UNKNOWN_REASON_CODE = "UNKNOWN";
    private static final String NO_MESSAGE = "";

    private static final Pattern SERVICE_KEY_IN_MESSAGE = Pattern.compile("serviceKey=[^&\\s]+");
    private static final String SERVICE_KEY_MASK = "serviceKey=***";

    private static final ErrorHandler KEEP_ERROR_BODY = (request, response) -> {
    };

    private final RestClient gbisRestClient;
    private final GbisProperties properties;
    private final ObjectMapper objectMapper;

    public GbisLocationSource(
        final RestClient gbisRestClient,
        final GbisProperties properties,
        final ObjectMapper objectMapper
    ) {
        this.gbisRestClient = gbisRestClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public GbisLocationResult read(
        final String routeId
    ) {
        try {
            final String body = gbisRestClient.get()
                .uri(builder -> builder
                    .path(BUS_LOCATION_PATH)
                    .queryParam("serviceKey", properties.serviceKey())
                    .queryParam("routeId", routeId)
                    .queryParam("format", "json")
                    .build())
                .retrieve()
                .onStatus(HttpStatusCode::isError, KEEP_ERROR_BODY)
                .body(String.class);
            return interpret(body);
        } catch (final RestClientException e) {
            return new NoResponse(maskServiceKey(e.getMessage()));
        }
    }

    private GbisLocationResult interpret(
        final String body
    ) {
        if (body == null || body.isBlank()) {
            return new NoResponse("Open API가 본문 없이 응답했다");
        }
        if (body.stripLeading().startsWith(XML_PREFIX)) {
            return interpretPortalError(body);
        }
        return interpretGbisResponse(body);
    }

    private GbisLocationResult interpretPortalError(
        final String body
    ) {
        final String reasonCode = firstGroupOr(REASON_CODE_IN_XML, body, UNKNOWN_REASON_CODE);
        return switch (PortalReasonCode.from(reasonCode)) {
            case DAILY_QUOTA_EXCEEDED -> new DailyQuotaExceeded();
            case PER_SECOND_QUOTA_EXCEEDED -> new PerSecondQuotaExceeded();
            case OTHER -> new GatewayRejected(
                reasonCode, firstGroupOr(ERROR_MESSAGE_IN_XML, body, NO_MESSAGE));
        };
    }

    private GbisLocationResult interpretGbisResponse(
        final String body
    ) {
        return parse(body)
            .map(this::interpretHeader)
            .orElseGet(() -> new GbisSystemError("Open API 응답을 읽지 못했다"));
    }

    private Optional<BusLocationResponse> parse(
        final String body
    ) {
        try {
            return Optional.of(objectMapper.readValue(body, BusLocationResponse.class));
        } catch (final JacksonException e) {
            return Optional.empty();
        }
    }

    private GbisLocationResult interpretHeader(
        final BusLocationResponse response
    ) {
        final Header header = response.response().header();
        return switch (GbisResultCode.from(header.resultCode())) {
            case SUCCESS -> new Success(header.queryTime(), busesOf(response));
            case NO_VEHICLES -> new NoVehicles(header.queryTime());
            case SYSTEM_FAILURE -> new GbisSystemError(header.resultMessage());
            case PARAMETER_MISSING -> new MissingRequiredParameter(header.resultMessage());
            case OTHER -> new GatewayRejected(
                String.valueOf(header.resultCode()), header.resultMessage());
        };
    }

    private List<BusLocation> busesOf(
        final BusLocationResponse response
    ) {
        if (response.response().body() == null
            || response.response().body().busLocations() == null) {
            return List.of();
        }
        return response.response().body().busLocations();
    }

    private String firstGroupOr(
        final Pattern pattern,
        final String text,
        final String fallback
    ) {
        final Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return fallback;
    }

    private String maskServiceKey(
        final String message
    ) {
        if (message == null) {
            return NO_MESSAGE;
        }
        return SERVICE_KEY_IN_MESSAGE.matcher(message).replaceAll(SERVICE_KEY_MASK);
    }
}
