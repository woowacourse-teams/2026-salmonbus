package com.gustler.backend.api.station.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gustler.backend.api.station.StationSearchTestData;
import com.gustler.backend.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@IntegrationTest
class StationSearchApiTest {
    private static final String SEARCH = "/api/v2/stations/search";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcClient jdbc;

    private StationSearchTestData data;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        data = new StationSearchTestData(jdbc);
        data.clear();
    }

    @Test
    void 검색_결과는_지도_칸과_같은_정류장_필드를_5분_공개_캐시로_준다() throws Exception {
        // given
        data.station("100000001", "판교역");
        data.salmonStop("204000057", "100000009", "판교역 2번출구", 127.11, 37.39);

        // when
        MockHttpServletResponse response = mvc.perform(get(SEARCH).param("q", "판교 역"))
            .andExpect(status().isOk())
            .andReturn().getResponse();
        JsonNode stations = json.readTree(response.getContentAsString(StandardCharsets.UTF_8)).path("stations");

        // then
        assertSoftly(softly -> {
            softly.assertThat(response.getHeader("Cache-Control")).isEqualTo("max-age=300, public");
            softly.assertThat(stations.size()).isEqualTo(2);
            softly.assertThat(stations.get(0).path("stationId").asText()).isEqualTo("100000001");
            softly.assertThat(stations.get(0).propertyNames()).containsExactly(
                "stationId", "name", "point", "mobileNo", "regionName", "direction", "salmonRouteIds");
            softly.assertThat(stations.get(0).path("point").path("x").asDouble()).isEqualTo(127.11);
            softly.assertThat(stations.get(1).path("direction").asText()).isEqualTo("다음정류장");
            softly.assertThat(stations.get(1).path("salmonRouteIds").get(0).asText()).isEqualTo("204000057");
        });
    }

    @Test
    void 결과가_없으면_빈_목록이다() throws Exception {
        // when
        JsonNode body = json.readTree(mvc.perform(get(SEARCH).param("q", "없는정류장"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        // then
        assertThat(body.path("stations").isArray()).isTrue();
        assertThat(body.path("stations").size()).isZero();
    }

    @Test
    void 검색_경로는_정류장_상세로_가지_않는다() throws Exception {
        // given
        data.station("100000001", "search");

        // when
        MockHttpServletResponse response = mvc.perform(get(SEARCH).param("q", "search"))
            .andExpect(status().isOk())
            .andReturn().getResponse();
        JsonNode body = json.readTree(response.getContentAsString(StandardCharsets.UTF_8));

        // then
        assertSoftly(softly -> {
            softly.assertThat(body.has("stations")).isTrue();
            softly.assertThat(body.has("routes")).isFalse();
            softly.assertThat(body.has("snapshot")).isFalse();
        });
    }

    @Test
    void 검색어가_없으면_공유_오류_코드로_거절한다() throws Exception {
        assertInvalid(get(SEARCH));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "%", "_", "...", "·()"})
    void 비었거나_기호만이거나_너무_긴_검색어는_거절한다(String q) throws Exception {
        assertInvalid(get(SEARCH).param("q", q));
    }

    @Test
    void 기호를_뺀_글자가_40자를_넘으면_거절한다() throws Exception {
        assertInvalid(get(SEARCH).param("q", "가".repeat(41)));
        assertInvalid(get(SEARCH).param("q", "가 ".repeat(41)));
    }

    @Test
    void 퍼센트와_밑줄_검색어는_전체를_돌려주지_않는다() throws Exception {
        // given
        IntStream.rangeClosed(1, 5).forEach(n -> data.station("10000000%d".formatted(n), "정류장" + n));

        // when & then
        assertInvalid(get(SEARCH).param("q", "%"));
        assertInvalid(get(SEARCH).param("q", "_"));
    }

    private void assertInvalid(RequestBuilder request) throws Exception {
        MockHttpServletResponse response = mvc.perform(request)
            .andExpect(status().isBadRequest())
            .andReturn().getResponse();
        JsonNode body = json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertSoftly(softly -> {
            softly.assertThat(body.path("code").asText()).isEqualTo("INVALID_STATION_SEARCH");
            softly.assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        });
    }
}
