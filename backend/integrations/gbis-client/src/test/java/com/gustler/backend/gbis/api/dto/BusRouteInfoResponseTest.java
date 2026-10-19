package com.gustler.backend.gbis.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.gbis.api.dto.BusRouteInfoResponse.RouteInfoItem;
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
            "downFirstTime":"05:00","downLastTime":"23:55",
            "turnStID":"208000069","turnStNm":"안양역"}}}}
        """;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void GBIS_필드명을_서버에서_이해하기_쉬운_변수명으로_매핑한다() {
        // when
        final BusRouteInfoResponse actual =
            objectMapper.readValue(ROUTE_INFO_JSON, BusRouteInfoResponse.class);

        // then 회차 순번은 이 응답에 없다. 정류소 목록이 준다
        assertThat(actual.response().body().routeInfo()).isEqualTo(new RouteInfoItem(
            "3330", "범계역", "강남역", "05:00", "22:35", "05:00", "23:55",
            null, null, null, null, null, null, null, null));
    }

    @Test
    void 요일별_최소_최대_배차간격을_평일_토요일_일요일_공휴일로_매핑한다() {
        // given
        String json = routeInfoWith("""
            "peekAlloc":10,"nPeekAlloc":20,"satPeekAlloc":12,"satNPeekAlloc":25,
            "sunPeekAlloc":15,"sunNPeekAlloc":30,"wePeekAlloc":16,"weNPeekAlloc":35""");

        // when
        RouteInfoItem actual = objectMapper.readValue(json, BusRouteInfoResponse.class)
            .response().body().routeInfo();

        // then
        assertThat(actual).isEqualTo(new RouteInfoItem(
            "3330", "범계역", "강남역", "05:00", "22:35", "05:00", "23:55",
            "10", "20", "12", "25", "15", "30", "16", "35"));
    }

    @Test
    void 배차간격의_숫자_문자열과_빈_값을_글자_그대로_읽는다() {
        // given
        String json = routeInfoWith("""
            "peekAlloc":"10","nPeekAlloc":" 20 ","satPeekAlloc":"","satNPeekAlloc":null,
            "sunPeekAlloc":"10.5","sunNPeekAlloc":-3,"wePeekAlloc":"abc","weNPeekAlloc":null""");

        // when
        RouteInfoItem actual = objectMapper.readValue(json, BusRouteInfoResponse.class)
            .response().body().routeInfo();

        // then
        assertThat(actual).isEqualTo(new RouteInfoItem(
            "3330", "범계역", "강남역", "05:00", "22:35", "05:00", "23:55",
            "10", " 20 ", "", null, "10.5", "-3", "abc", null));
    }

    @Test
    void 배차간격이_객체나_배열이어도_노선_기본_정보를_읽고_원문_JSON으로_넘긴다() {
        // given
        String json = routeInfoWith("""
            "peekAlloc":{},"nPeekAlloc":[10],"satPeekAlloc":true,"satNPeekAlloc":{"value":20}""");

        // when
        RouteInfoItem actual = objectMapper.readValue(json, BusRouteInfoResponse.class)
            .response().body().routeInfo();

        // then
        assertThat(actual).isEqualTo(new RouteInfoItem(
            "3330", "범계역", "강남역", "05:00", "22:35", "05:00", "23:55",
            "{}", "[10]", "true", "{\"value\":20}", null, null, null, null));
    }

    private static String routeInfoWith(
        String headwayFields
    ) {
        return """
            {"response":{"msgHeader":{
                "queryTime":"2026-08-28 11:14:04.911","resultCode":0,"resultMessage":"정상"},
              "msgBody":{"busRouteInfoItem":{
                "routeName":"3330","startStationName":"범계역","endStationName":"강남역",
                "upFirstTime":"05:00","upLastTime":"22:35",
                "downFirstTime":"05:00","downLastTime":"23:55",
                %s}}}}
            """.formatted(headwayFields);
    }
}
