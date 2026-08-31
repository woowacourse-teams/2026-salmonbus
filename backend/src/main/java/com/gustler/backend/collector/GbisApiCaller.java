package com.gustler.backend.collector;

import com.gustler.backend.collector.GbisRawResponse.NotReceived;
import com.gustler.backend.collector.GbisRawResponse.PortalRejected;
import com.gustler.backend.collector.GbisRawResponse.Received;
import com.gustler.backend.collector.dto.PortalErrorResponse;
import com.gustler.backend.collector.dto.PortalErrorResponse.CommonHeader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.ResponseSpec.ErrorHandler;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriUtils;
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
                .uri(addressOf(path, routeId))
                .retrieve()
                .onStatus(HttpStatusCode::isError, KEEP_ERROR_BODY)
                .body(byte[].class);
            return interpret(decode(raw));
        } catch (RestClientException e) {
            return new NotReceived(e.getMessage());
        }
    }

    /**
     * 주소를 다 만들어서 넘긴다. queryParam 에 맡기면 인증키가 깨진다.
     *
     * <p>UriComponentsBuilder 는 질의문자열에서 적법한 글자를 안 건드린다. 인증키에 든 {@code +} 가
     * 그대로 나가고, 서버는 그것을 공백으로 읽는다. 그래서 다른 키로 취급돼 등록되지 않은 인증키로 거절당한다.
     * 2026-08-31 실측이다. 같은 키를 직접 부르면 200 인데 이 경로로만 거절됐다.
     */
    private URI addressOf(
        String path,
        String routeId
    ) {
        return URI.create(properties.baseUrl() + path
            + "?serviceKey=" + encodedServiceKey()
            + "&routeId=" + routeId
            + "&format=json");
    }

    /**
     * 어느 형태로 받아도 같은 값이 나가게 한다.
     *
     * <p>공공데이터포털은 한때 인증키를 인코딩본과 디코딩본 두 벌로 줬다. 2025-08-21 개편으로 화면에서
     * 구분이 없어졌지만 그 전에 발급된 키는 여전히 두 벌이고, 어느 쪽을 환경변수에 넣었는지 알 수 없다.
     * 먼저 퍼센트 디코딩해서 원래 값으로 되돌린 다음 다시 인코딩한다. 디코딩본을 넣었으면 첫 단계가 아무 일도
     * 안 하고, 인코딩본을 넣었으면 이중 인코딩이 안 된다.
     */
    private String encodedServiceKey() {
        String raw = UriUtils.decode(properties.serviceKey(), StandardCharsets.UTF_8);
        return UriUtils.encode(raw, StandardCharsets.UTF_8);
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
