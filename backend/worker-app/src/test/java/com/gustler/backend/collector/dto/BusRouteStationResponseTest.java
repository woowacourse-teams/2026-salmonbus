package com.gustler.backend.collector.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.collector.dto.BusRouteStationResponse.RouteStationItem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import tools.jackson.databind.ObjectMapper;

/**
 * 이 JSON 은 실제 응답을 받아 적은 것이 아니라 공공데이터포털 문서를 보고 지어낸 것이다.
 * 서비스키가 아직 없어 대조하지 못했다. 위치정보 픽스처와 달리 실측이 아니다.
 */
@JsonTest
class BusRouteStationResponseTest {

    private static final String THREE_STATIONS_JSON = """
        {"response":{"msgHeader":{
            "queryTime":"2026-08-28 11:14:04.911","resultCode":0,"resultMessage":"정상"},
          "msgBody":{"busRouteStationList":[
            {"stationId":"205000217","stationName":"범계역","stationSeq":1},
            {"stationId":"277103149","stationName":"안양대교(경유)","stationSeq":2},
            {"stationId":"208000069","stationName":"안양역","stationSeq":3}]}}}
        """;
    private static final String SINGLE_STATION_AS_OBJECT_JSON = """
        {"response":{"msgHeader":{
            "queryTime":"2026-08-28 11:14:04.911","resultCode":0,"resultMessage":"정상"},
          "msgBody":{"busRouteStationList":
            {"stationId":"205000217","stationName":"범계역","stationSeq":1}}}}
        """;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void 정류소가_여럿이면_배열로_응답받고_리스트로_읽는다() {
        // when
        final BusRouteStationResponse actual =
            objectMapper.readValue(THREE_STATIONS_JSON, BusRouteStationResponse.class);

        // then
        assertThat(actual.response().body().stations()).hasSize(3);
    }

    @Test
    void 정류소가_하나면_객체로_응답받고_리스트로_읽는다() {
        // when
        final BusRouteStationResponse actual =
            objectMapper.readValue(SINGLE_STATION_AS_OBJECT_JSON, BusRouteStationResponse.class);

        // then
        assertThat(actual.response().body().stations()).hasSize(1);
    }

    @Test
    void GBIS_필드명을_서버에서_이해하기_쉬운_변수명으로_매핑한다() {
        // when
        final BusRouteStationResponse actual =
            objectMapper.readValue(THREE_STATIONS_JSON, BusRouteStationResponse.class);

        // then
        final RouteStationItem actualStation = actual.response().body().stations().getFirst();
        assertThat(actualStation)
            .isEqualTo(new RouteStationItem("205000217", "범계역", 1));
    }
}
