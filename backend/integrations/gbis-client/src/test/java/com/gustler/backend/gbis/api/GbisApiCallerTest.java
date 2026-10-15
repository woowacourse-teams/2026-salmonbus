package com.gustler.backend.gbis.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class GbisApiCallerTest {

    private static final String BASE_URL = "https://gbis.test";
    private static final String ROUTE_3330 = "204000057";
    private static final String BUS_LOCATION_PATH = "/buslocationservice/v2/getBusLocationListv2";
    private static final String EMPTY_JSON = "{}";

    /** 2025-08-21 전에 발급된 인증키는 base64 라 {@code +} {@code /} {@code =} 가 섞인다. */
    private static final String KEY_WITH_SYMBOLS = "ab+cd/ef=";
    private static final String KEY_ENCODED = "ab%2Bcd%2Fef%3D";

    private MockRestServiceServer openApi;
    private GbisApiCaller caller;

    private void bind(
        String serviceKey
    ) {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        openApi = MockRestServiceServer.bindTo(builder).build();
        caller = new GbisApiCaller(
            builder.build(),
            new GbisClientOptions(BASE_URL, serviceKey),
            new ObjectMapper());
    }

    @Test
    void 인증키에_든_기호를_퍼센트로_바꿔_보낸다() {
        // given
        bind(KEY_WITH_SYMBOLS);
        openApi.expect(requestTo(containsString("serviceKey=" + KEY_ENCODED)))
            .andRespond(withSuccess(EMPTY_JSON, MediaType.APPLICATION_JSON));

        // when
        caller.get(BUS_LOCATION_PATH, ROUTE_3330);

        // then
        openApi.verify();
    }

    @Test
    void 노선ID에_든_기호도_퍼센트로_바꿔_보낸다() {
        // given
        bind(KEY_WITH_SYMBOLS);
        openApi.expect(requestTo(containsString("routeId=204000057%26format%3Dxml")))
            .andRespond(withSuccess(EMPTY_JSON, MediaType.APPLICATION_JSON));

        // when
        caller.get(BUS_LOCATION_PATH, ROUTE_3330 + "&format=xml");

        // then
        openApi.verify();
    }

    @Test
    void 노선ID가_형식을_바꾸는_파라미터로_갈라지지_않는다() {
        // given
        bind(KEY_WITH_SYMBOLS);
        openApi.expect(requestTo(not(containsString("&format=xml"))))
            .andRespond(withSuccess(EMPTY_JSON, MediaType.APPLICATION_JSON));

        // when
        caller.get(BUS_LOCATION_PATH, ROUTE_3330 + "&format=xml");

        // then
        openApi.verify();
    }

    @Test
    void 정류장_조회는_routeId가_아닌_stationId로_한번만_전달한다() {
        bind(KEY_WITH_SYMBOLS);
        openApi.expect(requestTo(containsString("stationId=206000637%26format%3Dxml")))
            .andExpect(requestTo(not(containsString("routeId="))))
            .andRespond(withSuccess(EMPTY_JSON, MediaType.APPLICATION_JSON));
        caller.getByStation("/busarrivalservice/v2/getBusArrivalListv2", "206000637&format=xml", "a");
        openApi.verify();
    }

    @Test
    void 오류_본문이_비어도_HTTP상태와_재시도_힌트는_보존한다() {
        bind(KEY_WITH_SYMBOLS);
        openApi.expect(requestTo(containsString("stationId=206000637")))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "120"));
        var result = caller.getByStationWithMetadata("/busarrivalservice/v2/getBusArrivalListv2", "206000637", "a");
        assertThat(result.httpStatus()).isEqualTo(429);
        assertThat(result.retryAfter()).isEqualTo("120");
        openApi.verify();
    }

    @Test
    void 통신_예외의_URL에_포함된_인증키를_결과로_전달하지_않는다() {
        bind(KEY_WITH_SYMBOLS);
        openApi.expect(requestTo(containsString("stationId=206000637")))
            .andRespond(request -> { throw new java.io.IOException("serviceKey=" + KEY_WITH_SYMBOLS); });
        var result = caller.getByStationWithMetadata("/busarrivalservice/v2/getBusArrivalListv2", "206000637", "a");
        assertThat(result.response()).isInstanceOfSatisfying(GbisRawResponse.NotReceived.class,
            failure -> assertThat(failure.message()).doesNotContain(KEY_WITH_SYMBOLS).doesNotContain("serviceKey"));
        openApi.verify();
    }
}
