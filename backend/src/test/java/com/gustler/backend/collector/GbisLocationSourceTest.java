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
    private static final String BUS_LOCATION_PATH = "/buslocationservice/v2/getBusLocationListv2";
    private static final String SYSTEM_FAILURE_MESSAGE = "시스템 에러가 발생했습니다.";
    private static final String PARAMETER_MISSING_MESSAGE = "필수 파라미터가 누락되었습니다.";
    private static final String UNKNOWN_RESULT_MESSAGE = "알 수 없는 응답입니다.";
    private static final String PORTAL_ERROR_MESSAGE = "SERVICE ERROR";

    private static final String PORTAL_ERROR_XML = """
        <OpenAPI_ServiceResponse>
          <cmmMsgHeader>
            <errMsg>SERVICE ERROR</errMsg>
            <returnAuthMsg>%s</returnAuthMsg>
            <returnReasonCode>%s</returnReasonCode>
          </cmmMsgHeader>
        </OpenAPI_ServiceResponse>
        """;
    private static final String GBIS_HEADER_ONLY_JSON = """
        {"response":{"comMsgHeader":"","msgHeader":{
          "queryTime":"2026-08-20 09:00:00.000","resultCode":%d,"resultMessage":"%s"}}}
        """;

    private MockRestServiceServer openApi;
    private GbisLocationSource source;

    @BeforeEach
    void setUp() {
        final RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
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
        final GbisLocationResult actual = source.read(ROUTE_3330);

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
        final GbisLocationResult actual = source.read(ROUTE_3330);

        // then
        assertThat(actual).isInstanceOfSatisfying(Success.class,
            success -> assertThat(success.gbisQueryTime()).isEqualTo(QUERY_TIME_IN_FIXTURE));
    }

    @Test
    void 운행_차량이_0대인_것은_오류가_아니다() {
        // given
        respondWithJson(GBIS_HEADER_ONLY_JSON.formatted(4, "결과가 없습니다."));

        // when
        final GbisLocationResult actual = source.read(ROUTE_3330);

        // then
        assertThat(actual).isInstanceOf(NoVehicles.class);
    }

    @Test
    void GBIS_시스템_오류는_예외가_아니라_결과로_받는다() {
        // given
        respondWithJson(GBIS_HEADER_ONLY_JSON.formatted(1, SYSTEM_FAILURE_MESSAGE));

        // when
        final GbisLocationResult actual = source.read(ROUTE_3330);

        // then
        assertThat(actual).isInstanceOfSatisfying(GbisSystemError.class,
            error -> assertThat(error.message()).isEqualTo(SYSTEM_FAILURE_MESSAGE));
    }

    @Test
    void 필수_파라미터_누락도_예외가_아니라_결과로_받는다() {
        // given
        respondWithJson(GBIS_HEADER_ONLY_JSON.formatted(2, PARAMETER_MISSING_MESSAGE));

        // when
        final GbisLocationResult actual = source.read(ROUTE_3330);

        // then
        assertThat(actual).isInstanceOfSatisfying(MissingRequiredParameter.class,
            missing -> assertThat(missing.message()).isEqualTo(PARAMETER_MISSING_MESSAGE));
    }

    @Test
    void 명세에_없는_결과_코드가_와도_어떤_코드였는지_남긴다() {
        // given
        respondWithJson(GBIS_HEADER_ONLY_JSON.formatted(9, UNKNOWN_RESULT_MESSAGE));

        // when
        final GbisLocationResult actual = source.read(ROUTE_3330);

        // then
        assertThat(actual).isInstanceOfSatisfying(GatewayRejected.class, rejected -> {
            assertThat(rejected.reasonCode()).isEqualTo("9");
            assertThat(rejected.message()).isEqualTo(UNKNOWN_RESULT_MESSAGE);
        });
    }

    @Test
    void 일일_한도_초과는_XML로_오는_오류_본문에서_읽어낸다() {
        // given
        respondWithXml(PORTAL_ERROR_XML.formatted(
            "LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR", "22"));

        // when
        final GbisLocationResult actual = source.read(ROUTE_3330);

        // then
        assertThat(actual).isInstanceOf(DailyQuotaExceeded.class);
    }

    @Test
    void 초당_허용량_초과는_일일_한도_초과와_구분한다() {
        // given
        respondWithXml(PORTAL_ERROR_XML.formatted("LIMITED_NUMBER_OF_SERVICE_REQUESTS", "23"));

        // when
        final GbisLocationResult actual = source.read(ROUTE_3330);

        // then
        assertThat(actual).isInstanceOf(PerSecondQuotaExceeded.class);
    }

    @Test
    void 서비스키_오류처럼_모르는_거부는_사유_코드를_남긴다() {
        // given
        respondWithXml(PORTAL_ERROR_XML.formatted("SERVICE_KEY_IS_NOT_REGISTERED_ERROR", "30"));

        // when
        final GbisLocationResult actual = source.read(ROUTE_3330);

        // then
        assertThat(actual).isInstanceOfSatisfying(GatewayRejected.class, rejected -> {
            assertThat(rejected.reasonCode()).isEqualTo("30");
            assertThat(rejected.message()).isEqualTo(PORTAL_ERROR_MESSAGE);
        });
    }

    @Test
    void HTTP_상태가_오류여도_예외_대신_본문을_읽는다() {
        // given
        openApi.expect(requestTo(containsString(ROUTE_3330)))
            .andRespond(withServerError()
                .body(PORTAL_ERROR_XML.formatted("SERVICE ERROR", "22"))
                .contentType(MediaType.APPLICATION_XML));

        // when
        final GbisLocationResult actual = source.read(ROUTE_3330);

        // then
        assertThat(actual).isInstanceOf(DailyQuotaExceeded.class);
    }

    private void respondWithJson(
        final String body
    ) {
        openApi.expect(requestTo(containsString(ROUTE_3330)))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void respondWithXml(
        final String body
    ) {
        openApi.expect(requestTo(containsString(ROUTE_3330)))
            .andRespond(withSuccess(body, MediaType.APPLICATION_XML));
    }

    private static String fixture(
        final String name
    ) {
        try (InputStream stream =
                 GbisLocationSourceTest.class.getClassLoader().getResourceAsStream("gbis/" + name)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
