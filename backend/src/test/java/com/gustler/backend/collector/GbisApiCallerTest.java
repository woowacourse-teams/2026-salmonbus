package com.gustler.backend.collector;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class GbisApiCallerTest {

    private static final String BASE_URL = "https://gbis.test";
    private static final int DAILY_LIMIT = 10_000;
    private static final String ROUTE_3330 = "204000057";
    private static final String BUS_LOCATION_PATH = "/buslocationservice/v2/getBusLocationListv2";
    private static final String EMPTY_JSON = "{}";

    /**
     * 2025-08-21 전에 발급된 인증키는 base64 라 {@code +} {@code /} {@code =} 가 섞인다.
     * 포털은 같은 키를 퍼센트로 바꾼 판본도 같이 줬다.
     */
    private static final String KEY_WITH_SYMBOLS = "ab+cd/ef=";
    private static final String KEY_WITH_PERCENT = "ab%2Bcd%2Fef%3D";

    @Test
    void 인증키에_든_기호를_퍼센트로_바꿔_보낸다() {
        // given
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer openApi = MockRestServiceServer.bindTo(builder).build();
        GbisApiCaller caller = callerWith(builder, KEY_WITH_SYMBOLS);
        openApi.expect(requestTo(containsString("serviceKey=" + KEY_WITH_PERCENT)))
            .andRespond(withSuccess(EMPTY_JSON, MediaType.APPLICATION_JSON));

        // when
        caller.get(BUS_LOCATION_PATH, ROUTE_3330);

        // then
        openApi.verify();
    }

    @Test
    void 퍼센트로_바뀐_인증키를_받으면_한_번_더_바꾸지_않는다() {
        // given
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer openApi = MockRestServiceServer.bindTo(builder).build();
        GbisApiCaller caller = callerWith(builder, KEY_WITH_PERCENT);
        openApi.expect(requestTo(containsString("serviceKey=" + KEY_WITH_PERCENT)))
            .andRespond(withSuccess(EMPTY_JSON, MediaType.APPLICATION_JSON));

        // when
        caller.get(BUS_LOCATION_PATH, ROUTE_3330);

        // then
        openApi.verify();
    }

    private GbisApiCaller callerWith(
        RestClient.Builder builder,
        String serviceKey
    ) {
        return new GbisApiCaller(
            builder.build(),
            new GbisProperties(BASE_URL, serviceKey, DAILY_LIMIT),
            new ObjectMapper());
    }
}
