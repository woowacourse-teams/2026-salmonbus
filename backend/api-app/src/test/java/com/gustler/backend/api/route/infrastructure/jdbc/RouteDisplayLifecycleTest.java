package com.gustler.backend.api.route.infrastructure.jdbc;

import static com.gustler.backend.routegeometry.api.RouteDisplaySamples.manual;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.route.application.*;
import com.gustler.backend.routegeometry.engine.api.AutomaticRouteDisplay;
import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeneration;
import com.gustler.backend.api.route.controller.RouteGeometryController;
import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeometry.Source;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry;
import com.gustler.backend.routegeometry.api.RouteDisplayDbSamples;
import com.gustler.backend.support.PostgresTestContainer;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tools.jackson.databind.ObjectMapper;

import java.util.*;

@SpringBootTest(
        classes = RouteDisplayLifecycleTest.Config.class,
        properties = "route-geometry.presentation.enabled=true")
class RouteDisplayLifecycleTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({
        PostgresTestContainer.class,
        JdbcRouteGeometryQueryRepository.class,
        JdbcRouteDisplayRepository.class,
        RouteGeometryQueryService.class,
        RouteDisplaySelector.class
    })
    static class Config {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired RouteGeometryQueryService service;
    private static final RouteId ID = new RouteId("204000057");

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE route CASCADE").update();
        jdbc.sql("TRUNCATE route_display_generation_job CASCADE").update();
        RouteDisplayDbSamples.seed(jdbc, ID.value());
        installAutomatic(current());
    }

    private RouteGeometry current() {
        return service.get(ID, null);
    }

    private void installAutomatic(RouteGeometry geometry) {
        String digest = RouteDisplaySelector.input(geometry).digest();
        String evidence = geometry.shape().evidence().revision();
        String algorithm = AutomaticRouteDisplay.REVISION;
        String document = json.writeValueAsString(AutomaticRouteDisplay.build(geometry, digest));
        jdbc.sql("""
            INSERT INTO route_display_generation_job
                (generation_key,route_id,input_digest,evidence_revision,algorithm_revision,input_json,status)
            VALUES (?,?,?,?,?,CAST(? AS jsonb),'COMPLETED') ON CONFLICT DO NOTHING
            """).params(RouteDisplayGeneration.key(digest,evidence,algorithm),geometry.routeId(),digest,
                    evidence,algorithm,json.writeValueAsString(geometry.withDisplay(null))).update();
        jdbc.sql("""
            INSERT INTO route_display_automatic_artifact
                (generation_key,route_id,input_digest,evidence_revision,algorithm_revision,document,document_sha256)
            VALUES (?,?,?,?,?,?,?) ON CONFLICT DO NOTHING
            """).params(RouteDisplayGeneration.key(digest,evidence,algorithm),geometry.routeId(),digest,
                    evidence,algorithm,document,RouteDisplayGeneration.sha256(document)).update();
    }

    private void install(RouteGeometry geometry, double offset) {
        var document = manual(RouteDisplaySelector.input(geometry), offset);
        jdbc.sql(
                        "INSERT INTO"
                            + " route_display_geometry(route_id,input_digest,content_digest,document)"
                            + " SELECT id,?,?,CAST(? AS jsonb) FROM route WHERE public_route_id=?")
                .params(
                        document.inputDigest(),
                        UUID.randomUUID().toString().replace("-", "") + "a".repeat(32),
                        json.writeValueAsString(document),
                        ID.value())
                .update();
    }

    @Test
    void 실제DB와_HTTP에서_수동A_자동B_수동B_비활성화를_전환한다() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new RouteGeometryController(service)).build();
        String path = "/api/v2/routes/" + ID.value() + "/geometry";
        RouteGeometry raw = current();
        install(raw, 0.0001);
        var first =
                mvc.perform(get(path).param("includeDiagnostics", "true"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.displaySource").value("MANUAL_REVIEWED"))
                        .andExpect(jsonPath("$.points[0].x").value(127))
                        .andExpect(jsonPath("$.busStops[0].displayPoint.x").value(127.0001))
                        .andReturn();
        String etag = first.getResponse().getHeader("ETag");
        mvc.perform(get(path).param("includeDiagnostics", "true").header("If-None-Match", etag)).andExpect(status().isNotModified());
        long version = raw.referenceVersionId();
        jdbc.sql(
                        "INSERT INTO"
                            + " route_stop_location_history(route_version_id,stop_order,stop_id,observed_at,x,y)"
                            + " VALUES (?,2,'206000638','2026-10-15T00:00:00Z',127.0101,37.01)")
                .param(version)
                .update();
        var changed =
                mvc.perform(get(path).param("includeDiagnostics", "true").header("If-None-Match", etag))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.displaySource").value("AUTOMATIC_FALLBACK"))
                        .andExpect(jsonPath("$.busStops[0].displayPoint").doesNotExist())
                        .andReturn();
        assertThat(current().referenceVersionId()).isEqualTo(version);
        assertThat(changed.getResponse().getHeader("ETag")).isNotEqualTo(etag);
        install(current(), 0.0002);
        RouteGeometry reviewed = current();
        assertThat(reviewed.display().source()).isEqualTo(Source.MANUAL_REVIEWED);
        assertThat(reviewed.display().stationPositions().get(1).x()).isEqualTo(127.0002);
        jdbc.sql("UPDATE route_display_geometry SET disabled_at=CURRENT_TIMESTAMP").update();
        assertThat(current().display().source()).isEqualTo(Source.AUTOMATIC_FALLBACK);
        assertThat(current().revision()).isNotEqualTo(reviewed.revision());
    }

    @Test
    void 이름만_변경하면_수동을_재사용하고_형상만_변경하면_자동으로_바꾼다() {
        install(current(), 0.0001);
        jdbc.sql("UPDATE route SET display_name='새 이름'").update();
        assertThat(current().display().source()).isEqualTo(Source.MANUAL_REVIEWED);
        jdbc.sql(
                        "UPDATE route_geometry SET"
                            + " points='[{\"x\":127,\"y\":37},{\"x\":127.01001,\"y\":37.01},{\"x\":127,\"y\":37}]'::jsonb")
                .update();
        assertThat(current().display().source()).isEqualTo(Source.AUTOMATIC_FALLBACK);
    }

    @Test
    void 손상된수동은_자동으로_내리고_현재판본에_형상이없으면_과거형상을_섞지않는다() {
        install(current(), 0.0001);
        jdbc.sql("UPDATE route_display_geometry SET document='{}'::jsonb").update();
        assertThat(current().display().source()).isEqualTo(Source.AUTOMATIC_FALLBACK);
        jdbc.sql("DELETE FROM route_geometry").update();
        assertThat(current().status()).isEqualTo(RouteGeometry.Availability.UNAVAILABLE);
        assertThat(current().display()).isNull();
        assertThat(current().points()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null"})
    void 보조캐시가_손상돼도_정상원본으로_자동표시한다(String invalidEvidence) {
        jdbc.sql("UPDATE route_geometry SET evidence=CAST(? AS jsonb)").param(invalidEvidence).update();
        // Changed evidence cannot reuse the earlier automatic artifact before validation.
        assertThat(current().display().quality().status().name()).isEqualTo("WITHHELD");
        installAutomatic(current());
        assertThat(current().display().paths()).isNotNull();
        assertThat(current().display().connections()).hasSize(3);
        assertThat(current().display().source()).isEqualTo(Source.AUTOMATIC_FALLBACK);
        assertThat(current().display().evidence())
                .extracting(value -> value.status())
                .containsExactly("INVALID");
        assertThat(current().points()).hasSize(3);
    }
    @Test void GPS거리_경고는_표시를_보류하지않고_원본좌표_복구시_품질만_갱신한다() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new RouteGeometryController(service)).build();
        String path="/api/v2/routes/"+ID.value()+"/geometry";
        jdbc.sql("UPDATE route_stop_location_history SET y=y+0.0003 WHERE stop_order=1").update();
        installAutomatic(current());
        var warned=mvc.perform(get(path).param("includeDiagnostics", "true")).andExpect(status().isOk())
            .andExpect(jsonPath("$.displayQuality.status").value("REVIEW_REQUIRED"))
            .andExpect(jsonPath("$.displayQuality.issues[*].code", org.hamcrest.Matchers.hasItem("STOP_CONNECTION_GAP")))
            .andExpect(jsonPath("$.displayPaths.UP.points").isNotEmpty())
            .andExpect(jsonPath("$.points.length()").value(3))
            .andExpect(jsonPath("$.busStops[0].point.y").value(37.0003))
            .andExpect(jsonPath("$.busStops[0].pathConnection.segmentIndex").value(0)).andReturn();
        jdbc.sql("UPDATE route_stop_location_history SET y=37 WHERE stop_order=1").update();
        mvc.perform(get(path).param("includeDiagnostics", "true").header("If-None-Match",warned.getResponse().getHeader("ETag")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.displayQuality.status").value("PASSED"))
            .andExpect(jsonPath("$.busStops[0].pathConnection.segmentIndex").value(0));
    }

    @Test
    void 공식_방문근거없는_큰GPS이격은_계속_보류하고_기본응답의_원본선도_숨긴다() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new RouteGeometryController(service)).build();
        String path="/api/v2/routes/"+ID.value()+"/geometry";
        jdbc.sql("UPDATE route_stop_location_history SET y=y+0.0006 WHERE stop_order=1").update();
        installAutomatic(current());
        mvc.perform(get(path).param("includeDiagnostics", "true")).andExpect(status().isOk())
                .andExpect(jsonPath("$.displayQuality.status").value("WITHHELD"))
                .andExpect(jsonPath("$.displayPaths").doesNotExist())
                .andExpect(jsonPath("$.points.length()").value(3))
                .andExpect(jsonPath("$.busStops[0].point.y").value(37.0006))
                .andExpect(jsonPath("$.busStops[0].pathConnection").doesNotExist());
        mvc.perform(get(path)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.reason").value("DISPLAY_WITHHELD"))
                .andExpect(jsonPath("$.points").isEmpty())
                .andExpect(jsonPath("$.paths").isEmpty())
                .andExpect(jsonPath("$.busStops[0].point.y").value(37.0006))
                .andExpect(jsonPath("$.busStops[0].pathConnection").doesNotExist());
    }

}
