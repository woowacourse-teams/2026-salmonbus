package com.gustler.backend.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
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
    private static final String REAL_LOOKING_SERVICE_KEY = "abcd1234SECRETKEYVALUE";
    private static final String ROUTE_3330 = "204000057";
    private static final String QUERY_TIME_IN_FIXTURE = "2026-08-19 11:14:04.911";

    private static final String PORTAL_ERROR_XML = """
        <OpenAPI_ServiceResponse>
          <cmmMsgHeader>
            <errMsg>SERVICE ERROR</errMsg>
            <returnAuthMsg>%s</returnAuthMsg>
            <returnReasonCode>%s</returnReasonCode>
          </cmmMsgHeader>
        </OpenAPI_ServiceResponse>
        """;
    private static final String MESSAGE_WITH_BARE_KEY =
        "인증키 " + REAL_LOOKING_SERVICE_KEY + " 가 등록되지 않았습니다.";

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
            new GbisProperties(BASE_URL, REAL_LOOKING_SERVICE_KEY),
            new ObjectMapper());
    }

    @Test
    void 정상_응답이면_차량_목록을_담는다() {
        // given
        respondWithJson(fixture("location-two-vehicles.json"));

        // when
        final GbisLocationResult actual = source.read(ROUTE_3330);

        // then
        assertThat(actual).isInstanceOfSatisfying(Success.class,
            success -> assertThat(success.buses()).hasSize(2));
    }

    @Test
    void 정상_응답이면_상류가_알려준_조회_시각을_그대로_들고_온다() {
        // given
        respondWithJson(fixture("location-two-vehicles.json"));

        // when
        final GbisLocationResult actual = source.read(ROUTE_3330);

        // then
        assertThat(actual).isInstanceOfSatisfying(Success.class,
            success -> assertThat(success.gbisQueryTime()).isEqualTo(QUERY_TIME_IN_FIXTURE));
    }

    @Test
    void 운행_차량이_0대면_장애가_아니라_NoVehicles_다() {
        // given
        respondWithJson(GBIS_HEADER_ONLY_JSON.formatted(4, "결과가 없습니다."));

        // when
        final GbisLocationResult actual = source.read(ROUTE_3330);

        // then
        assertThat(actual).isInstanceOf(NoVehicles.class);
    }

    @Test
    void GBIS_시스템_에러는_값으로_돌려준다() {
        // given
        respondWithJson(GBIS_HEADER_ONLY_JSON.formatted(1, "시스템 에러가 발생했습니다."));

        // when
        final GbisLocationResult actual = source.read(ROUTE_3330);

        // then
        assertThat(actual).isInstanceOf(GbisSystemError.class);
    }

    @Test
    void 필수_파라미터_누락도_예외가_아니라_값이다() {
        // given
        respondWithJson(GBIS_HEADER_ONLY_JSON.formatted(2, "필수 파라미터가 누락되었습니다."));

        // when
        final GbisLocationResult actual = source.read(ROUTE_3330);

        // then
        assertThat(actual).isInstanceOf(MissingRequiredParameter.class);
    }

    @Test
    void 명세에_없는_결과_코드는_사유_코드를_들고_온다() {
        // given
        respondWithJson(GBIS_HEADER_ONLY_JSON.formatted(9, "알 수 없는 응답입니다."));

        // when
        final GbisLocationResult actual = source.read(ROUTE_3330);

        // then
        assertThat(actual).isInstanceOfSatisfying(GatewayRejected.class,
            rejected -> assertThat(rejected.reasonCode()).isEqualTo("9"));
    }

    @Test
    void 포털_오류는_JSON_을_요청해도_XML_로_와서_첫_글자로_가른다() {
        // given
        respondWithXml(PORTAL_ERROR_XML.formatted(
            "LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR", "22"));

        // when
        final GbisLocationResult actual = source.read(ROUTE_3330);

        // then
        assertThat(actual).isInstanceOf(DailyQuotaExceeded.class);
    }

    @Test
    void 초당_허용량_초과는_일_한도_초과와_다른_값이다() {
        // given
        respondWithXml(PORTAL_ERROR_XML.formatted("LIMITED_NUMBER_OF_SERVICE_REQUESTS", "23"));

        // when
        final GbisLocationResult actual = source.read(ROUTE_3330);

        // then
        assertThat(actual).isInstanceOf(PerSecondQuotaExceeded.class);
    }

    @Test
    void 키_오류처럼_사람이_고쳐야_하는_거부는_사유_코드를_들고_온다() {
        // given
        respondWithXml(PORTAL_ERROR_XML.formatted("SERVICE_KEY_IS_NOT_REGISTERED_ERROR", "30"));

        // when
        final GbisLocationResult actual = source.read(ROUTE_3330);

        // then
        assertThat(actual).isInstanceOfSatisfying(GatewayRejected.class,
            rejected -> assertThat(rejected.reasonCode()).isEqualTo("30"));
    }

    @Test
    void HTTP_에러여도_예외를_던지지_않고_본문을_본다() {
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

    @Test
    void 응답을_못_받으면_서비스키를_가린_채로_사유를_남긴다() {
        // given
        openApi.expect(requestTo(containsString(ROUTE_3330)))
            .andRespond(request -> {
                throw new IOException("connect timed out serviceKey=" + REAL_LOOKING_SERVICE_KEY);
            });

        // when
        final GbisLocationResult actual = source.read(ROUTE_3330);

        // then
        assertThat(actual).isInstanceOfSatisfying(NoResponse.class, noResponse -> {
            assertThat(noResponse.message()).doesNotContain(REAL_LOOKING_SERVICE_KEY);
            assertThat(noResponse.message()).contains("serviceKey=***");
        });
    }

    @Test
    void 서비스키가_serviceKey_형태가_아니어도_값이_같으면_마스킹한다() {
        // given
        openApi.expect(requestTo(containsString(ROUTE_3330)))
            .andRespond(request -> {
                throw new IOException(MESSAGE_WITH_BARE_KEY);
            });

        // when
        final GbisLocationResult actual = source.read(ROUTE_3330);

        // then
        assertThat(actual).isInstanceOfSatisfying(NoResponse.class,
            noResponse -> assertThat(noResponse.message())
                .doesNotContain(REAL_LOOKING_SERVICE_KEY));
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
