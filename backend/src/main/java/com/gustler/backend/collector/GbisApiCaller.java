package com.gustler.backend.collector;

import com.gustler.backend.collector.GbisRawResponse.NotReceived;
import com.gustler.backend.collector.GbisRawResponse.PortalRejected;
import com.gustler.backend.collector.GbisRawResponse.Received;
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

/**
 * GBIS 를 부르고 본문을 글자로 받는 자리. 어느 API 를 부르든 여기까지는 같다.
 *
 * <p>포털 오류는 GBIS 응답과 봉투가 아예 다르고, JSON 으로 올 때와 XML 로 올 때가 있다.
 * 그 처리를 API 마다 복사하면 한쪽만 고쳐지는 날이 온다.
 */
@Component
public class GbisApiCaller {

    private static final String PORTAL_ERROR_ROOT = "OpenAPI_ServiceResponse";
    private static final Pattern REASON_CODE_IN_XML = Pattern.compile("<returnReasonCode>(.*?)</returnReasonCode>");
    private static final Pattern ERROR_CODE_IN_XML = Pattern.compile("<errMsg>(.*?)</errMsg>");
    private static final Pattern AUTH_MESSAGE_IN_XML = Pattern.compile("<returnAuthMsg>(.*?)</returnAuthMsg>");
    private static final String UNKNOWN_REASON_CODE = "UNKNOWN";
    private static final String NO_MESSAGE = "";
    private static final String EMPTY_BODY = "Open API가 본문 없이 응답했다";

    /** HTTP 상태가 오류여도 본문을 버리지 않는다. 포털 오류는 4xx·5xx 로 오면서 사유를 본문에 담는다. */
    private static final ErrorHandler KEEP_ERROR_BODY = (request, response) -> {
    };

    private final RestClient gbisRestClient;
    private final GbisProperties properties;
    private final ObjectMapper objectMapper;

    public GbisApiCaller(
        RestClient gbisRestClient,
        GbisProperties properties,
        ObjectMapper objectMapper
    ) {
        this.gbisRestClient = gbisRestClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    // TODO 로깅 혹은 저장 시 serviceKey 를 마스킹해야 한다.
    public GbisRawResponse get(
        String path,
        String routeId
    ) {
        try {
            byte[] raw = gbisRestClient.get()
                .uri(builder -> builder
                    .path(path)
                    .queryParam("serviceKey", properties.serviceKey())
                    .queryParam("routeId", routeId)
                    .queryParam("format", "json")
                    .build())
                .retrieve()
                .onStatus(HttpStatusCode::isError, KEEP_ERROR_BODY)
                .body(byte[].class);
            return interpret(decode(raw));
        } catch (RestClientException e) {
            return new NotReceived(e.getMessage());
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

    private GbisRawResponse interpret(
        String body
    ) {
        if (body == null || body.isBlank()) {
            return new NotReceived(EMPTY_BODY);
        }
        if (body.contains(PORTAL_ERROR_ROOT)) {
            return portalRejectionOf(body);
        }
        return new Received(body);
    }

    private GbisRawResponse portalRejectionOf(
        String body
    ) {
        CommonHeader header = parsePortalError(body)
            .orElseGet(() -> portalHeaderFromXml(body));

        return new PortalRejected(
            PortalReasonCode.from(header.returnReasonCode()),
            header.returnReasonCode(),
            header.errMsg(),
            header.returnAuthMsg());
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
}
