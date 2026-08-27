package com.gustler.backend.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

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
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class GbisLocationSourceTest {

    private static final String BASE_URL = "https://gbis.test";
    private static final String SERVICE_KEY = "fake-service-key-for-test";
    private static final String ROUTE_3330 = "204000057";
    private static final String QUERY_TIME_IN_FIXTURE = "2026-08-19 11:14:04.911";
    private static final String QUERY_TIME_IN_TEMPLATE = "2026-08-20 09:00:00.000";
    private static final String QUERY_TIME_IN_PARAMETER_MISSING = "2026-08-21 19:43:51.069";
    private static final String BUS_LOCATION_PATH = "/buslocationservice/v2/getBusLocationListv2";
    private static final String SYSTEM_FAILURE_MESSAGE = "시스템 에러가 발생했습니다.";
    private static final String PARAMETER_MISSING_MESSAGE = "필수 요청 Parameter 가 존재하지 않습니다.";
    private static final String UNKNOWN_RESULT_MESSAGE = "알 수 없는 응답입니다.";
    private static final String PORTAL_ERROR_CODE = "SERVICE_KEY_IS_NOT_REGISTERED_ERROR";
    private static final String PORTAL_ERROR_MESSAGE = "등록되지 않은 서비스키";
    private static final String PARSE_FAILURE = "Open API 응답을 파싱하지 못했다: ";

    private static final String PORTAL_ERROR_XML = """
        <OpenAPI_ServiceResponse>
          <cmmMsgHeader>
            <errMsg>SERVICE ERROR</errMsg>
            <returnAuthMsg>등록되지 않은 서비스키</returnAuthMsg>
            <returnReasonCode>%s</returnReasonCode>
          </cmmMsgHeader>
        </OpenAPI_ServiceResponse>
        """;
    private static final String PORTAL_ERROR_JSON = """
        {"OpenAPI_ServiceResponse":{"cmmMsgHeader":{
          "errMsg":"SERVICE ERROR",
          "returnAuthMsg":"등록되지 않은 서비스키",
          "returnReasonCode":"%s"}}}
        """;
    private static final String GBIS_HEADER_ONLY_JSON = """
        {"response":{"comMsgHeader":"","msgHeader":{
          "queryTime":"2026-08-20 09:00:00.000","resultCode":%d,"resultMessage":"%s"}}}
        """;

    private MockRestServiceServer openApi;
    private GbisLocationSource source;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        openApi = MockRestServiceServer.bindTo(builder).build();
        source = new GbisLocationSource(
            builder.build(),
            new GbisProperties(BASE_URL, SERVICE_KEY),
            new ObjectMapper());
    }

    @Test
    void Open_API를_부를_때_서비스키와_노선ID와_JSON_형식을_보낸다() {
        // given
        openApi.expect(requestTo(containsString(BUS_LOCATION_PATH)))
            .andExpect(queryParam("serviceKey", SERVICE_KEY))
            .andExpect(queryParam("routeId", ROUTE_3330))
            .andExpect(queryParam("format", "json"))
            .andRespond(withSuccess(
                fixture("location-two-vehicles.json"), MediaType.APPLICATION_JSON));

        // when
        source.read(ROUTE_3330);

        // then
        openApi.verify();
    }

    @Test
    void 정상_응답이면_응답에_담긴_차량을_모두_읽는다() {
        // given
        respondWithJson(fixture("location-two-vehicles.json"));

        // when
        GbisLocationResult actual = source.read(ROUTE_3330);

        // then
        assertThat(actual)
            .isInstanceOfSatisfying(
                Success.class,
                success -> assertThat(success.buses()).hasSize(2));
    }

    @Test
    void 정상_응답이면_Open_API가_준_조회_시각도_그대로_담는다() {
        // given
        respondWithJson(fixture("location-two-vehicles.json"));

        // when
        GbisLocationResult actual = source.read(ROUTE_3330);

        // then
        assertThat(actual).isInstanceOfSatisfying(Success.class,
            success -> assertThat(success.gbisQueryTime()).isEqualTo(QUERY_TIME_IN_FIXTURE));
    }

    @Test
    void 결과_코드가_정상인데_본문이_없으면_읽지_못한_것으로_받는다() {
        // given
        respondWithJson(GBIS_HEADER_ONLY_JSON.formatted(0, "정상적으로 처리되었습니다."));

        // when
        GbisLocationResult actual = source.read(ROUTE_3330);

        // then
        assertThat(actual).isInstanceOf(UnreadableResponse.class);
    }

    @Test
    void 운행_차량이_0대인_것은_오류가_아니다() {
        // given
        respondWithJson(fixture("location-no-vehicles.json"));

        // when
        GbisLocationResult actual = source.read(ROUTE_3330);

        // then
        assertThat(actual).isInstanceOf(NoVehicles.class);
    }

    @Test
    void GBIS_시스템_오류는_예외가_아니라_결과로_받는다() {
        // given
        respondWithJson(GBIS_HEADER_ONLY_JSON.formatted(1, SYSTEM_FAILURE_MESSAGE));

        // when
        GbisLocationResult actual = source.read(ROUTE_3330);

        // then
        assertThat(actual).isInstanceOfSatisfying(GbisSystemError.class, error -> {
            assertThat(error.message()).isEqualTo(SYSTEM_FAILURE_MESSAGE);
            assertThat(error.gbisQueryTime()).isEqualTo(QUERY_TIME_IN_TEMPLATE);
        });
    }

    @Test
    void 필수_파라미터_누락도_예외가_아니라_결과로_받는다() {
        // given
        respondWithJson(fixture("location-parameter-missing.json"));

        // when
        GbisLocationResult actual = source.read(ROUTE_3330);

        // then
        assertThat(actual).isInstanceOfSatisfying(MissingRequiredParameter.class, missing -> {
            assertThat(missing.message()).isEqualTo(PARAMETER_MISSING_MESSAGE);
            assertThat(missing.gbisQueryTime()).isEqualTo(QUERY_TIME_IN_PARAMETER_MISSING);
        });
    }

    @Test
    void GBIS가_명세에_없는_결과_코드를_주면_그_코드를_남긴다() {
        // given
        respondWithJson(GBIS_HEADER_ONLY_JSON.formatted(9, UNKNOWN_RESULT_MESSAGE));

        // when
        GbisLocationResult actual = source.read(ROUTE_3330);

        // then
        assertThat(actual).isInstanceOfSatisfying(UnknownGbisResultCode.class, unknown -> {
            assertThat(unknown.resultCode()).isEqualTo(9);
            assertThat(unknown.message()).isEqualTo(UNKNOWN_RESULT_MESSAGE);
            assertThat(unknown.gbisQueryTime()).isEqualTo(QUERY_TIME_IN_TEMPLATE);
        });
    }

    @Test
    void 파싱에_실패하면_원인을_함께_담는다() {
        // given
        respondWithJson("{not json");

        // when
        GbisLocationResult actual = source.read(ROUTE_3330);

        // then
        assertThat(actual).isInstanceOfSatisfying(UnreadableResponse.class,
            unreadable -> assertThat(unreadable.message())
                .startsWith(PARSE_FAILURE)
                .hasSizeGreaterThan(PARSE_FAILURE.length()));
    }

    @Test
    void 우리가_모르는_JSON이_와도_예외_대신_결과로_받는다() {
        // given
        respondWithJson("""
            {"somethingElse":{"whatever":1}}
            """);

        // when
        GbisLocationResult actual = source.read(ROUTE_3330);

        // then
        assertThat(actual).isInstanceOf(UnreadableResponse.class);
    }

    @Test
    void 일일_한도_초과는_XML로_오는_오류_본문에서_읽어낸다() {
        // given
        respondWithPortalError("22");

        // when
        GbisLocationResult actual = source.read(ROUTE_3330);

        // then
        assertThat(actual).isInstanceOf(DailyQuotaExceeded.class);
    }

    @Test
    void 일일_한도_초과가_JSON으로_와도_XML과_같은_값으로_받는다() {
        // given
        respondWithPortalErrorAsJson("22");

        // when
        GbisLocationResult actual = source.read(ROUTE_3330);

        // then
        assertThat(actual).isInstanceOf(DailyQuotaExceeded.class);
    }

    @Test
    void 포털_거부가_JSON으로_와도_같은_사유_코드를_남긴다() {
        // given
        respondWithJson(fixture("portal-key-not-registered.json"));

        // when
        GbisLocationResult actual = source.read(ROUTE_3330);

        // then
        assertThat(actual).isInstanceOfSatisfying(GatewayRejected.class, rejected -> {
            assertThat(rejected.reasonCode()).isEqualTo("30");
            assertThat(rejected.errorCode()).isEqualTo(PORTAL_ERROR_CODE);
            assertThat(rejected.message()).isEqualTo(PORTAL_ERROR_MESSAGE);
        });
    }

    @Test
    void 초당_허용량_초과는_일일_한도_초과와_구분한다() {
        // given
        respondWithPortalError("23");

        // when
        GbisLocationResult actual = source.read(ROUTE_3330);

        // then
        assertThat(actual).isInstanceOf(PerSecondQuotaExceeded.class);
    }

    @Test
    void 포털이_서비스키를_거부하면_사유_코드를_남긴다() {
        // given
        respondWithXml(fixture("portal-key-not-registered.xml"));

        // when
        GbisLocationResult actual = source.read(ROUTE_3330);

        // then
        assertThat(actual).isInstanceOfSatisfying(GatewayRejected.class, rejected -> {
            assertThat(rejected.reasonCode()).isEqualTo("30");
            assertThat(rejected.errorCode()).isEqualTo(PORTAL_ERROR_CODE);
            assertThat(rejected.message()).isEqualTo(PORTAL_ERROR_MESSAGE);
        });
    }

    @Test
    void HTTP_상태가_오류여도_예외_대신_본문을_읽는다() {
        // given
        openApi.expect(requestTo(containsString(ROUTE_3330)))
            .andRespond(withServerError()
                .body(PORTAL_ERROR_XML.formatted("22"))
                .contentType(MediaType.APPLICATION_XML));

        // when
        GbisLocationResult actual = source.read(ROUTE_3330);

        // then
        assertThat(actual).isInstanceOf(DailyQuotaExceeded.class);
    }

    private void respondWithJson(
        String body
    ) {
        openApi.expect(requestTo(containsString(ROUTE_3330)))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void respondWithXml(
        String body
    ) {
        openApi.expect(requestTo(containsString(ROUTE_3330)))
            .andRespond(withSuccess(body, MediaType.APPLICATION_XML));
    }

    private void respondWithPortalError(
        String reasonCode
    ) {
        openApi.expect(requestTo(containsString(ROUTE_3330)))
            .andRespond(withSuccess(
                PORTAL_ERROR_XML.formatted(reasonCode), MediaType.APPLICATION_XML));
    }

    private void respondWithPortalErrorAsJson(
        String reasonCode
    ) {
        openApi.expect(requestTo(containsString(ROUTE_3330)))
            .andRespond(withSuccess(
                PORTAL_ERROR_JSON.formatted(reasonCode), MediaType.APPLICATION_JSON));
    }

    private static InputStream openFixture(
        String name
    ) {
        InputStream stream =
            GbisLocationSourceTest.class.getClassLoader().getResourceAsStream("gbis/" + name);
        if (stream == null) {
            throw new IllegalArgumentException("픽스처를 찾지 못했다: gbis/" + name);
        }
        return stream;
    }

    private static String fixture(
        String name
    ) {
        try (InputStream stream = openFixture(name)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
