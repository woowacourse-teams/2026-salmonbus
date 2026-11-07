package com.gustler.backend.api.http.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gustler.backend.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@IntegrationTest
@ActiveProfiles("api-docs-local")
class StationMapApiDocsContractTest {
    private static final String TILES = "/api/v2/stations/tiles/14/13976/6354";
    private static final String TILES_PATTERN = "/api/v2/stations/tiles/{zoom}/{x}/{y}";
    private static final String DETAIL_PATTERN = "/api/v2/stations/{stationId}";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcClient jdbc;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbc.sql("TRUNCATE bus_station,bus_station_tile,station_seed_dataset").update();
    }

    @Test
    void 생성한_공유명세의_응답상태와_헤더와_예시와_참조스키마가_일치한다() throws Exception {
        // given
        JsonNode contract = json.readTree(Files.readString(
            Path.of(System.getProperty("station.map.openapi.contract"))
        ));

        // when
        String body = mvc.perform(get("/api/docs/openapi"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode generated = json.readTree(body);
        Files.createDirectories(Path.of("build/reports"));
        Files.writeString(Path.of("build/reports/station-map-generated-openapi.json"), body);

        // then
        assertSoftly(softly -> {
            for (String path : List.of(TILES_PATTERN, DETAIL_PATTERN)) {
                softly.assertThat(generated.path("paths").path(path))
                    .as(path)
                    .isEqualTo(contract.path("paths").path(path));
            }
            for (Map.Entry<String, JsonNode> entry : contract.at("/components/schemas").properties()) {
                softly.assertThat(generated.at("/components/schemas").path(entry.getKey()))
                    .as(entry.getKey())
                    .isEqualTo(entry.getValue());
            }
            softly.assertThat(generated.path("paths").has("/internal/stations/refresh")).isFalse();
            softly.assertThat(generated.path("paths").path(TILES_PATTERN)
                .at("/get/responses/304").has("content")).isFalse();
        });
    }

    @Test
    void 기초명부_응답의_기준일과_검증시각을_구분한다() throws Exception {
        // given
        seedStation();

        // when
        MockHttpServletResponse response = mvc.perform(get(TILES))
            .andExpect(status().isOk())
            .andReturn().getResponse();
        JsonNode body = json.readTree(response.getContentAsString(StandardCharsets.UTF_8));

        // then
        assertThat(body.path("stations").size()).isEqualTo(1);
        assertSoftly(softly -> {
            softly.assertThat(body.path("status").asText()).isEqualTo("READY");
            softly.assertThat(body.path("baselineDate").asText()).isEqualTo("2026-10-20");
            softly.assertThat(body.has("verifiedAt")).isTrue();
            softly.assertThat(body.path("verifiedAt").isNull()).isTrue();
            softly.assertThat(body.path("stations").get(0).path("stationId").asText()).isEqualTo("999999991");
            softly.assertThat(response.getHeader("Cache-Control")).contains("max-age=300");
        });
    }

    @Test
    void 같은_ETag로_재검증하면_본문_없이_반환한다() throws Exception {
        // given
        seedStation();
        MockHttpServletResponse first = mvc.perform(get(TILES))
            .andExpect(status().isOk())
            .andReturn().getResponse();

        // when
        MockHttpServletResponse matched = mvc.perform(get(TILES).header("If-None-Match", first.getHeader("ETag")))
            .andExpect(status().isNotModified())
            .andReturn().getResponse();

        // then
        assertThat(matched.getContentAsByteArray()).isEmpty();
    }

    @Test
    void 목록이_바뀌면_이전_ETag와_다른_응답을_반환한다() throws Exception {
        // given
        seedStation();
        MockHttpServletResponse first = mvc.perform(get(TILES))
            .andExpect(status().isOk())
            .andReturn().getResponse();
        jdbc.sql("UPDATE bus_station SET name='변경' WHERE station_id='999999991'").update();

        // when
        MockHttpServletResponse changed = mvc.perform(get(TILES).header("If-None-Match", first.getHeader("ETag")))
            .andExpect(status().isOk())
            .andReturn().getResponse();

        // then
        assertThat(changed.getHeader("ETag")).isNotEqualTo(first.getHeader("ETag"));
    }

    @Test
    void 명부도_검증기록도_없으면_PENDING이며_캐시하지_않는다() throws Exception {
        // when
        MockHttpServletResponse response = mvc.perform(get(TILES))
            .andExpect(status().isOk())
            .andReturn().getResponse();
        JsonNode body = json.readTree(response.getContentAsString(StandardCharsets.UTF_8));

        // then
        assertSoftly(softly -> {
            softly.assertThat(body.path("status").asText()).isEqualTo("PENDING");
            softly.assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
            softly.assertThat(response.getHeader("ETag")).isNull();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v2/stations/tiles/13/13976/6354", "/api/v2/stations/tiles/14/16384/0"})
    void 잘못된_칸은_공유_오류_코드를_반환한다(String path) throws Exception {
        // when
        MockHttpServletResponse response = mvc.perform(get(path))
            .andExpect(status().isBadRequest())
            .andReturn().getResponse();
        JsonNode body = json.readTree(response.getContentAsString(StandardCharsets.UTF_8));

        // then
        assertSoftly(softly -> {
            softly.assertThat(body.path("code").asText()).isEqualTo("INVALID_STATION_TILE");
            softly.assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        });
    }

    @Test
    void 등록되지_않은_상세_ID는_오류를_반환하며_원천으로_중계하지_않는다() throws Exception {
        // when
        MockHttpServletResponse response = mvc.perform(get("/api/v2/stations/999999999"))
            .andExpect(status().isNotFound())
            .andReturn().getResponse();
        JsonNode body = json.readTree(response.getContentAsString(StandardCharsets.UTF_8));

        // then
        assertThat(body.path("code").asText()).isEqualTo("STATION_NOT_FOUND");
    }

    private void seedStation() {
        jdbc.sql("INSERT INTO station_seed_dataset VALUES('GBIS',DATE '2026-10-20',repeat('a',64),1,now())").update();
        jdbc.sql("""
            INSERT INTO bus_station(station_id,name,x,y,tile_x,tile_y,first_seen_at,last_seen_at,baseline_date)
            VALUES('999999991','예시',127.11,37.39,13976,6354,now(),now(),DATE '2026-10-20')
            """).update();
    }
}
