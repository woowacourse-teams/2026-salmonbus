package com.gustler.backend.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.gustler.backend.collector.GbisRouteResult.Failed;
import com.gustler.backend.collector.GbisRouteResult.Success;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class GbisRouteSourceTest {

    private static final String BASE_URL = "https://gbis.test";
    private static final String SERVICE_KEY = "fake-service-key-for-test";
    private static final int DAILY_LIMIT = 10_000;
    private static final String ROUTE_3330 = "204000057";

    private static final String ROUTE_INFO_PATH = "/busrouteservice/v2/getBusRouteInfoItemv2";
    private static final String ROUTE_STATION_PATH = "/busrouteservice/v2/getBusRouteStationListv2";

    private static final String ROUTE_INFO_JSON = """
        {"response":{"msgHeader":{
            "queryTime":"2026-08-28 11:14:04.911","resultCode":%d,"resultMessage":"정상"},
          "msgBody":{"busRouteInfoItem":{
            "routeName":"3330","startStationName":"범계역","endStationName":"강남역",
            "upFirstTime":"05:00","upLastTime":"22:35",
            "downFirstTime":"05:00","downLastTime":"23:55","turnSeq":2}}}}
        """;
    private static final String THREE_STATIONS_JSON = """
        {"response":{"msgHeader":{
            "queryTime":"2026-08-28 11:14:04.911","resultCode":0,"resultMessage":"정상"},
          "msgBody":{"busRouteStationList":[
            {"stationId":"205000217","stationName":"범계역","stationSeq":1},
            {"stationId":"277103149","stationName":"안양대교(경유)","stationSeq":2},
            {"stationId":"208000069","stationName":"안양역","stationSeq":3}]}}}
        """;
    private static final String NO_STATIONS_JSON = """
        {"response":{"msgHeader":{
            "queryTime":"2026-08-28 11:14:04.911","resultCode":0,"resultMessage":"정상"},
          "msgBody":{"busRouteStationList":[]}}}
        """;
    private static final String REPEATED_SEQUENCE_JSON = """
        {"response":{"msgHeader":{
            "queryTime":"2026-08-28 11:14:04.911","resultCode":0,"resultMessage":"정상"},
          "msgBody":{"busRouteStationList":[
            {"stationId":"205000217","stationName":"범계역","stationSeq":1},
            {"stationId":"208000069","stationName":"안양역","stationSeq":1}]}}}
        """;
    private static final String PORTAL_ERROR_XML = """
        <OpenAPI_ServiceResponse><cmmMsgHeader>
          <errMsg>SERVICE ERROR</errMsg>
          <returnAuthMsg>일일 한도 초과</returnAuthMsg>
          <returnReasonCode>22</returnReasonCode>
        </cmmMsgHeader></OpenAPI_ServiceResponse>
        """;

    private MockRestServiceServer openApi;
    private GbisRouteSource source;

    @BeforeEach
    void setUp() {
        final RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        openApi = MockRestServiceServer.bindTo(builder).build();
        ObjectMapper objectMapper = new ObjectMapper();
        source = new GbisRouteSource(
            new GbisApiCaller(
                builder.build(),
                new GbisProperties(BASE_URL, SERVICE_KEY, DAILY_LIMIT),
                objectMapper),
            objectMapper);
    }

    @Test
    void 노선정보와_정류소를_모두_읽으면_경유_정류소를_순서대로_담는다() {
        // given
        respondWith(ROUTE_INFO_JSON.formatted(0), THREE_STATIONS_JSON);

        // when
        final GbisRouteResult actual = source.read(ROUTE_3330);

        // then
        assertThat(((Success) actual).route().stops().stops())
            .extracting(RouteStop::stopId)
            .containsExactly("205000217", "277103149", "208000069");
    }

    @Test
    void 노선정보에서_표시명과_기점과_종점을_읽는다() {
        // given
        respondWith(ROUTE_INFO_JSON.formatted(0), THREE_STATIONS_JSON);

        // when
        final GbisRouteResult actual = source.read(ROUTE_3330);

        // then
        assertThat(((Success) actual).route())
            .extracting(UpstreamRoute::displayName, UpstreamRoute::startStopName, UpstreamRoute::endStopName)
            .containsExactly("3330", "범계역", "강남역");
    }

    @Test
    void 노선정보에서_첫차와_막차_시각을_읽는다() {
        // given
        respondWith(ROUTE_INFO_JSON.formatted(0), THREE_STATIONS_JSON);

        // when
        final GbisRouteResult actual = source.read(ROUTE_3330);

        // then
        assertThat(((Success) actual).route().timetable())
            .isEqualTo(new RouteTimetable("05:00", "22:35", "05:00", "23:55"));
    }

    @Test
    void 회차_순번_뒤의_정류소는_하행이다() {
        // given
        respondWith(ROUTE_INFO_JSON.formatted(0), THREE_STATIONS_JSON);

        // when
        final GbisRouteResult actual = source.read(ROUTE_3330);

        // then
        assertThat(((Success) actual).route().stops().stops())
            .extracting(RouteStop::direction)
            .containsExactly(StopDirection.UP, StopDirection.UP, StopDirection.DOWN);
    }

    @Test
    void 노선정보_결과_코드가_정상이_아니면_읽지_못한_것으로_받는다() {
        // given
        openApi.expect(requestTo(Matchers.containsString(ROUTE_INFO_PATH)))
            .andRespond(withSuccess(ROUTE_INFO_JSON.formatted(1), MediaType.APPLICATION_JSON));

        // when
        final GbisRouteResult actual = source.read(ROUTE_3330);

        // then
        assertThat(((Failed) actual).reason()).contains("기본 정보를 읽지 못했다");
    }

    @Test
    void 경유_정류소가_하나도_없으면_읽지_못한_것으로_받는다() {
        // given
        respondWith(ROUTE_INFO_JSON.formatted(0), NO_STATIONS_JSON);

        // when
        final GbisRouteResult actual = source.read(ROUTE_3330);

        // then
        assertThat(((Failed) actual).reason()).contains("경유 정류소를 읽지 못했다");
    }

    @Test
    void 순번이_겹치는_정류소_목록은_판본으로_열지_않는다() {
        // given
        respondWith(ROUTE_INFO_JSON.formatted(0), REPEATED_SEQUENCE_JSON);

        // when
        final GbisRouteResult actual = source.read(ROUTE_3330);

        // then
        assertThat(((Failed) actual).reason()).contains("정류소 목록이 성립하지 않는다");
    }

    @Test
    void 포털이_하루_한도_초과로_막으면_읽지_못한_것으로_받는다() {
        // given
        openApi.expect(requestTo(Matchers.containsString(ROUTE_INFO_PATH)))
            .andRespond(withSuccess(PORTAL_ERROR_XML, MediaType.APPLICATION_XML));

        // when
        final GbisRouteResult actual = source.read(ROUTE_3330);

        // then
        assertThat(((Failed) actual).reason()).contains("기본 정보를 읽지 못했다");
    }

    private void respondWith(
        final String routeInfoBody,
        final String stationBody
    ) {
        openApi.expect(requestTo(Matchers.containsString(ROUTE_INFO_PATH)))
            .andRespond(withSuccess(routeInfoBody, MediaType.APPLICATION_JSON));
        openApi.expect(requestTo(Matchers.containsString(ROUTE_STATION_PATH)))
            .andRespond(withSuccess(stationBody, MediaType.APPLICATION_JSON));
    }
}
