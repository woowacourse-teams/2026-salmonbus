package com.gustler.backend.collector.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.collector.dto.BusRouteInfoResponse.RouteInfoItem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import tools.jackson.databind.ObjectMapper;

/**
 * 이 JSON 은 실제 응답을 받아 적은 것이 아니라 공공데이터포털 문서를 보고 지어낸 것이다.
 * 서비스키가 아직 없어 대조하지 못했다.
 */
@JsonTest
class BusRouteInfoResponseTest {

    private static final String ROUTE_INFO_JSON = """
        {"response":{"msgHeader":{
            "queryTime":"2026-08-28 11:14:04.911","resultCode":0,"resultMessage":"정상"},
          "msgBody":{"busRouteInfoItem":{
            "routeName":"3330","startStationName":"범계역","endStationName":"강남역",
            "upFirstTime":"05:00","upLastTime":"22:35",
            "downFirstTime":"05:00","downLastTime":"23:55","turnSeq":2}}}}
        """;
    private static final String ONE_WAY_ROUTE_JSON = """
        {"response":{"msgHeader":{
            "queryTime":"2026-08-28 11:14:04.911","resultCode":0,"resultMessage":"정상"},
          "msgBody":{"busRouteInfoItem":{
            "routeName":"3330","startStationName":"범계역","endStationName":"강남역",
            "upFirstTime":"05:00","upLastTime":"22:35",
            "downFirstTime":"05:00","downLastTime":"23:55"}}}}
        """;

    private static final String ZERO_TURN_JSON = """
        {"response":{"msgHeader":{
            "queryTime":"2026-08-28 11:14:04.911","resultCode":0,"resultMessage":"정상"},
          "msgBody":{"busRouteInfoItem":{
            "routeName":"3330","startStationName":"범계역","endStationName":"강남역",
            "upFirstTime":"05:00","upLastTime":"22:35",
            "downFirstTime":"05:00","downLastTime":"23:55","turnSeq":0}}}}
        """;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void GBIS_필드명을_서버에서_이해하기_쉬운_변수명으로_매핑한다() {
        // when
        final BusRouteInfoResponse actual =
            objectMapper.readValue(ROUTE_INFO_JSON, BusRouteInfoResponse.class);

        // then
        assertThat(actual.response().body().routeInfo()).isEqualTo(new RouteInfoItem(
            "3330", "범계역", "강남역", "05:00", "22:35", "05:00", "23:55", 2));
    }

    @Test
    void 회차_순번이_0_이면_회차_없음으로_읽는다() {
        // when
        final BusRouteInfoResponse actual =
            objectMapper.readValue(ZERO_TURN_JSON, BusRouteInfoResponse.class);

        // then 정류소 순번은 1 부터라 0 인 회차 지점은 있을 수 없다
        assertThat(actual.response().body().routeInfo().turnSequence()).isNull();
    }

    @Test
    void 회차_순번을_안_주는_노선은_회차_순번이_비어_있다() {
        // when
        final BusRouteInfoResponse actual =
            objectMapper.readValue(ONE_WAY_ROUTE_JSON, BusRouteInfoResponse.class);

        // then
        assertThat(actual.response().body().routeInfo().turnSequence()).isNull();
    }
}
