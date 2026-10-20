package com.gustler.backend.routecatalog.infrastructure.gbis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.gustler.backend.gbis.api.GbisApiCaller;
import com.gustler.backend.gbis.api.GbisClientOptions;
import com.gustler.backend.gbis.api.GbisRouteInfoSource;
import com.gustler.backend.routecatalog.domain.HeadwayRange;
import com.gustler.backend.routecatalog.domain.RouteHeadways;
import com.gustler.backend.routecatalog.domain.RouteSourceResult;
import com.gustler.backend.routecatalog.domain.RouteSourceResult.Failed;
import com.gustler.backend.routecatalog.domain.RouteSourceResult.Success;
import com.gustler.backend.routecatalog.domain.RouteStop;
import com.gustler.backend.routecatalog.domain.RouteTimetable;
import com.gustler.backend.routecatalog.domain.ServiceDay;
import com.gustler.backend.routecatalog.domain.StopDirection;
import com.gustler.backend.routecatalog.domain.UpstreamRoute;
import java.util.EnumSet;
import java.util.Set;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.Builder;
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
            "downFirstTime":"05:00","downLastTime":"23:55"}}}}
        """;
    private static final String ROUTE_INFO_WITH_HEADWAYS_JSON = """
        {"response":{"msgHeader":{
            "queryTime":"2026-08-28 11:14:04.911","resultCode":0,"resultMessage":"정상"},
          "msgBody":{"busRouteInfoItem":{
            "routeName":"3330","startStationName":"범계역","endStationName":"강남역",
            "upFirstTime":"05:00","upLastTime":"22:35",
            "downFirstTime":"05:00","downLastTime":"23:55",
            %s}}}}
        """;
    private static final String THREE_STATIONS_JSON = """
        {"response":{"msgHeader":{
            "queryTime":"2026-08-28 11:14:04.911","resultCode":0,"resultMessage":"정상"},
          "msgBody":{"busRouteStationList":[
            {"stationId":"205000217","stationName":"범계역","stationSeq":1,"turnSeq":2,"turnYn":"N"},
            {"stationId":"277103149","stationName":"안양대교(경유)","stationSeq":2,"turnSeq":2,"turnYn":"Y"},
            {"stationId":"208000069","stationName":"안양역","stationSeq":3,"turnSeq":2,"turnYn":"N"}]}}}
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
        Builder builder = RestClient.builder().baseUrl(BASE_URL);
        openApi = MockRestServiceServer.bindTo(builder).build();
        ObjectMapper objectMapper = new ObjectMapper();
        source = new GbisRouteSource(new GbisRouteInfoSource(
            new GbisApiCaller(
                builder.build(),
                new GbisClientOptions(BASE_URL, SERVICE_KEY),
                objectMapper),
            objectMapper));
    }

    @Test
    void 노선정보와_정류소를_모두_읽으면_경유_정류소를_순서대로_담는다() {
        // given
        respondWith(ROUTE_INFO_JSON.formatted(0), THREE_STATIONS_JSON);

        // when
        RouteSourceResult actual = source.read(ROUTE_3330);

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
        RouteSourceResult actual = source.read(ROUTE_3330);

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
        RouteSourceResult actual = source.read(ROUTE_3330);

        // then
        assertThat(((Success) actual).route().timetable())
            .isEqualTo(new RouteTimetable("05:00", "22:35", "05:00", "23:55", RouteHeadways.none()));
    }

    @Test
    void 노선정보에서_평일_토요일_일요일_공휴일의_최소_최대_배차간격을_읽는다() {
        // given
        respondWith(ROUTE_INFO_WITH_HEADWAYS_JSON.formatted("""
            "peekAlloc":10,"nPeekAlloc":20,"satPeekAlloc":12,"satNPeekAlloc":25,
            "sunPeekAlloc":15,"sunNPeekAlloc":30,"wePeekAlloc":16,"weNPeekAlloc":35"""), THREE_STATIONS_JSON);

        // when
        RouteSourceResult actual = source.read(ROUTE_3330);

        // then
        assertThat(((Success) actual).route().timetable().headways()).isEqualTo(new RouteHeadways(
            new HeadwayRange(10, 20), new HeadwayRange(12, 25), new HeadwayRange(15, 30), new HeadwayRange(16, 35)));
    }

    @Test
    void 숫자_문자열_배차간격을_분으로_읽고_최소와_최대가_같아도_받는다() {
        // given
        respondWith(ROUTE_INFO_WITH_HEADWAYS_JSON.formatted("""
            "peekAlloc":" 10 ","nPeekAlloc":"20","satPeekAlloc":"15","satNPeekAlloc":15"""), THREE_STATIONS_JSON);

        // when
        RouteSourceResult actual = source.read(ROUTE_3330);

        // then
        assertThat(((Success) actual).route().timetable().headways()).isEqualTo(new RouteHeadways(
            new HeadwayRange(10, 20), new HeadwayRange(15, 15), null, null));
    }

    @Test
    void 성립하지_않는_요일은_읽지_못한_요일로_표시하고_노선과_시간표와_정류소는_그대로_읽는다() {
        // given
        respondWith(ROUTE_INFO_WITH_HEADWAYS_JSON.formatted("""
            "peekAlloc":0,"nPeekAlloc":0,"satPeekAlloc":20,"satNPeekAlloc":10,
            "sunPeekAlloc":15,"wePeekAlloc":"10.5","weNPeekAlloc":"abc"
            """), THREE_STATIONS_JSON);

        // when
        RouteSourceResult actual = source.read(ROUTE_3330);

        // then
        UpstreamRoute route = ((Success) actual).route();
        assertThat(route.timetable()).isEqualTo(new RouteTimetable("05:00", "22:35", "05:00", "23:55",
            new RouteHeadways(null, null, null, null, EnumSet.allOf(ServiceDay.class))));
        assertThat(route.stops().stops()).hasSize(3);
        assertThat(route.displayName()).isEqualTo("3330");
    }

    @Test
    void 배차간격이_음수거나_하루를_넘으면_그_요일만_읽지_못한_요일이고_둘_다_비면_없는_요일이다() {
        // given
        respondWith(ROUTE_INFO_WITH_HEADWAYS_JSON.formatted("""
            "peekAlloc":-5,"nPeekAlloc":20,"satPeekAlloc":10,"satNPeekAlloc":1441,
            "sunPeekAlloc":"","sunNPeekAlloc":"","wePeekAlloc":8,"weNPeekAlloc":16"""), THREE_STATIONS_JSON);

        // when
        RouteSourceResult actual = source.read(ROUTE_3330);

        // then
        assertThat(((Success) actual).route().timetable().headways())
            .isEqualTo(new RouteHeadways(null, null, null, new HeadwayRange(8, 16),
                Set.of(ServiceDay.WEEKDAY, ServiceDay.SATURDAY)));
    }

    @Test
    void 배차간격이_객체나_배열이면_그_요일을_읽지_못한_요일로_표시하고_노선은_읽는다() {
        // given
        respondWith(ROUTE_INFO_WITH_HEADWAYS_JSON.formatted("""
            "peekAlloc":{},"nPeekAlloc":[10],"satPeekAlloc":true,"satNPeekAlloc":true"""), THREE_STATIONS_JSON);

        // when
        RouteSourceResult actual = source.read(ROUTE_3330);

        // then
        assertThat(((Success) actual).route().timetable().headways()).isEqualTo(new RouteHeadways(
            null, null, null, null, Set.of(ServiceDay.WEEKDAY, ServiceDay.SATURDAY)));
    }

    @Test
    void 배차간격을_함께_읽어도_GBIS_호출은_노선정보와_정류소_두_번이다() {
        // given
        respondWith(ROUTE_INFO_WITH_HEADWAYS_JSON.formatted("""
            "peekAlloc":10,"nPeekAlloc":20"""), THREE_STATIONS_JSON);

        // when
        source.read(ROUTE_3330);

        // then
        openApi.verify();
        assertThat(source.requiredCallsPerRead()).isEqualTo(2);
    }

    @Test
    void 회차_순번_뒤의_정류소는_하행이다() {
        // given
        respondWith(ROUTE_INFO_JSON.formatted(0), THREE_STATIONS_JSON);

        // when
        RouteSourceResult actual = source.read(ROUTE_3330);

        // then
        assertThat(((Success) actual).route().stops().stops())
            .extracting(RouteStop::direction)
            .containsExactly(StopDirection.UP, StopDirection.UP, StopDirection.DOWN);
    }

    @Test
    void 회차_순번이_없으면_단방향_노선으로_읽어낸다() {
        // given
        respondWith(ROUTE_INFO_JSON.formatted(0),
            THREE_STATIONS_JSON.replace(",\"turnSeq\":2,\"turnYn\":\"N\"", "")
                .replace(",\"turnSeq\":2,\"turnYn\":\"Y\"", ""));

        // when
        RouteSourceResult actual = source.read(ROUTE_3330);

        // then
        assertThat(((Success) actual).route().stops().turnSequence()).isNull();
    }

    @Test
    void 회차_표시가_없으면_단방향_노선으로_읽어낸다() {
        // given
        // 순번은 실려 왔는데 어느 정류소가 회차 지점인지 표시가 없다
        respondWith(ROUTE_INFO_JSON.formatted(0),
            THREE_STATIONS_JSON.replace("\"turnYn\":\"Y\"", "\"turnYn\":\"N\""));

        // when
        RouteSourceResult actual = source.read(ROUTE_3330);

        // then
        assertThat(((Success) actual).route().stops().turnSequence()).isNull();
    }

    @Test
    void 회차_표시가_둘이면_단방향_노선으로_읽어낸다() {
        // given
        respondWith(ROUTE_INFO_JSON.formatted(0),
            THREE_STATIONS_JSON.replace("\"stationSeq\":3,\"turnSeq\":2,\"turnYn\":\"N\"",
            "\"stationSeq\":3,\"turnSeq\":2,\"turnYn\":\"Y\""));

        // when
        RouteSourceResult actual = source.read(ROUTE_3330);

        // then
        assertThat(((Success) actual).route().stops().turnSequence()).isNull();
    }

    @Test
    void 회차_순번이_빠진_행이_하나라도_있으면_단방향_노선으로_읽어낸다() {
        // given
        // 남은 행만으로 고르면 그 노선이 정말 회차하는지 알 수 없다
        respondWith(ROUTE_INFO_JSON.formatted(0),
            THREE_STATIONS_JSON.replace("\"stationSeq\":1,\"turnSeq\":2,", "\"stationSeq\":1,"));

        // when
        RouteSourceResult actual = source.read(ROUTE_3330);

        // then
        assertThat(((Success) actual).route().stops().turnSequence()).isNull();
    }

    @Test
    void 회차_순번이_행마다_다르면_단방향_노선으로_읽어낸다() {
        // given
        respondWith(ROUTE_INFO_JSON.formatted(0),
            THREE_STATIONS_JSON.replace("\"stationSeq\":1,\"turnSeq\":2,",
            "\"stationSeq\":1,\"turnSeq\":3,"));

        // when
        RouteSourceResult actual = source.read(ROUTE_3330);

        // then
        assertThat(((Success) actual).route().stops().turnSequence()).isNull();
    }

    @Test
    void 회차_순번과_회차_표시가_어긋나면_단방향_노선으로_읽어낸다() {
        // given
        // 순번은 2 인데 표시는 3번 정류소에 붙어 있다
        respondWith(ROUTE_INFO_JSON.formatted(0),
            THREE_STATIONS_JSON.replace("\"stationSeq\":2,\"turnSeq\":2,\"turnYn\":\"Y\"",
            "\"stationSeq\":2,\"turnSeq\":2,\"turnYn\":\"N\"")
                .replace("\"stationSeq\":3,\"turnSeq\":2,\"turnYn\":\"N\"",
                "\"stationSeq\":3,\"turnSeq\":2,\"turnYn\":\"Y\""));

        // when
        RouteSourceResult actual = source.read(ROUTE_3330);

        // then
        // 잘못 고른 순번으로 판본을 열면 정류소 절반의 방향이 뒤집힌다
        assertThat(((Success) actual).route().stops().turnSequence()).isNull();
    }

    @Test
    void 노선정보_결과_코드가_정상이_아니면_읽지_못한_것으로_받는다() {
        // given
        openApi.expect(requestTo(Matchers.containsString(ROUTE_INFO_PATH)))
            .andRespond(withSuccess(ROUTE_INFO_JSON.formatted(1), MediaType.APPLICATION_JSON));

        // when
        RouteSourceResult actual = source.read(ROUTE_3330);

        // then
        assertThat(((Failed) actual).reason()).contains("기본 정보를 읽지 못했다");
    }

    @Test
    void 경유_정류소가_하나도_없으면_읽지_못한_것으로_받는다() {
        // given
        respondWith(ROUTE_INFO_JSON.formatted(0), NO_STATIONS_JSON);

        // when
        RouteSourceResult actual = source.read(ROUTE_3330);

        // then
        assertThat(((Failed) actual).reason()).contains("경유 정류소를 읽지 못했다");
    }

    @Test
    void 순번이_겹치는_정류소_목록은_판본으로_열지_않는다() {
        // given
        respondWith(ROUTE_INFO_JSON.formatted(0), REPEATED_SEQUENCE_JSON);

        // when
        RouteSourceResult actual = source.read(ROUTE_3330);

        // then
        assertThat(((Failed) actual).reason()).contains("정류소 목록이 성립하지 않는다");
    }

    @Test
    void 포털이_하루_한도_초과로_막으면_읽지_못한_것으로_받는다() {
        // given
        openApi.expect(requestTo(Matchers.containsString(ROUTE_INFO_PATH)))
            .andRespond(withSuccess(PORTAL_ERROR_XML, MediaType.APPLICATION_XML));

        // when
        RouteSourceResult actual = source.read(ROUTE_3330);

        // then
        assertThat(((Failed) actual).reason()).contains("기본 정보를 읽지 못했다");
    }

    private void respondWith(
        String routeInfoBody,
        String stationBody
    ) {
        openApi.expect(requestTo(Matchers.containsString(ROUTE_INFO_PATH)))
            .andRespond(withSuccess(routeInfoBody, MediaType.APPLICATION_JSON));
        openApi.expect(requestTo(Matchers.containsString(ROUTE_STATION_PATH)))
            .andRespond(withSuccess(stationBody, MediaType.APPLICATION_JSON));
    }
}
