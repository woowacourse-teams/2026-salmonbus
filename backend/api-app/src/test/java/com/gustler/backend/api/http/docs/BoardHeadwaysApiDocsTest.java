package com.gustler.backend.api.http.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gustler.backend.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@IntegrationTest
@ActiveProfiles("api-docs-local")
class BoardHeadwaysApiDocsTest {

    private static final List<String> DAYS = List.of("weekday", "saturday", "sunday", "holiday");

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    private JsonNode document;

    @BeforeEach
    void setUp() throws Exception {
        String body = MockMvcBuilders.webAppContextSetup(applicationContext).build()
            .perform(get("/api/docs/openapi"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        document = objectMapper.readTree(body);
    }

    @Test
    void 노선_배차간격은_기존_필수_필드를_바꾸지_않는_선택_필드다() {
        // when
        JsonNode actual = document.at("/components/schemas/BoardRouteResponse");

        // then
        assertThat(actual.at("/properties/headways/$ref").asText())
            .isEqualTo("#/components/schemas/RouteHeadwaysResponse");
        assertThat(actual.at("/properties/headways/description").asText())
            .contains("요일별 최소·최대 배차간격(분)", "실제 도착 간격이나 평균이 아니다", "마지막으로 받은 정상 값을 유지한다",
                "공휴일인지는 판정하지 않는다");
        assertThat(actual.path("required")).extracting(JsonNode::asText).containsExactlyInAnyOrder(
            "id", "displayName", "startStopName", "endStopName", "status", "turnSequence", "directions",
            "referenceVersionId");
    }

    @Test
    void 네_요일은_항상_있고_값이_없으면_null이다() {
        // when
        JsonNode actual = document.at("/components/schemas/RouteHeadwaysResponse");

        // then
        assertThat(actual.path("required")).extracting(JsonNode::asText).containsExactlyInAnyOrderElementsOf(DAYS);
        assertThat(actual.path("properties").size()).isEqualTo(DAYS.size());
        for (String day : DAYS) {
            assertThat(actual.at("/properties/" + day + "/anyOf").toString()).as(day)
                .contains("#/components/schemas/HeadwayRangeResponse", "\"null\"");
        }
        assertThat(actual.at("/properties/holiday/description").asText()).contains("일요일 값으로 대신하지 않는다");
    }

    @Test
    void 배차간격은_1분부터_1440분까지의_최소와_최대다() {
        // when
        JsonNode actual = document.at("/components/schemas/HeadwayRangeResponse");

        // then
        assertThat(actual.path("required")).extracting(JsonNode::asText)
            .containsExactlyInAnyOrder("minMinutes", "maxMinutes");
        for (String bound : List.of("minMinutes", "maxMinutes")) {
            assertThat(actual.at("/properties/" + bound + "/minimum").asInt()).as(bound).isEqualTo(1);
            assertThat(actual.at("/properties/" + bound + "/maximum").asInt()).as(bound).isEqualTo(1440);
        }
    }

    @Test
    void 보드_응답_예시가_네_요일_배차간격을_보여준다() {
        // when
        JsonNode examples = document.at(
            "/paths/~1api~1v1~1routes~1{routeId}~1board/get/responses/200/content/application~1json/examples");

        // then
        for (String name : List.of("FORECAST_READY", "PREPARING")) {
            JsonNode headways = examples.at("/" + name + "/value/route/headways");
            assertThat(headways.properties()).as(name).extracting(Map.Entry::getKey)
                .containsExactlyElementsOf(DAYS);
        }
        assertThat(examples.at("/FORECAST_READY/value/route/headways/weekday/minMinutes").asInt()).isEqualTo(10);
        assertThat(examples.at("/FORECAST_READY/value/route/headways/holiday").isNull()).isTrue();
    }
}
