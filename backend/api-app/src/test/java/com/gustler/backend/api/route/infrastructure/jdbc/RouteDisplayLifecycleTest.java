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
    @Autowired JdbcRouteDisplayRepository displays;
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
        installArtifact(geometry, AutomaticRouteDisplay.REVISION,
                json.writeValueAsString(AutomaticRouteDisplay.build(geometry, digest)), null);
    }

    private void installArtifact(RouteGeometry geometry, String algorithm, String document, String storedSha256) {
        String digest = RouteDisplaySelector.input(geometry).digest();
        String evidence = geometry.shape().evidence().revision();
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
                    evidence,algorithm,document,
                    storedSha256 == null ? RouteDisplayGeneration.sha256(document) : storedSha256).update();
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
        putGenerationJob(current(),RouteDisplayGeneration.SOURCE_REVISION,"COMPLETED",1,5,null);
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

    private void putGenerationJob(RouteGeometry g,String algorithm,String status,int attempts,int limit,String lease) {
        String digest=RouteDisplayGeneration.input(g).digest(),evidence=g.shape().evidence().revision();
        jdbc.sql("""
            INSERT INTO route_display_generation_job
                (generation_key,route_id,input_digest,evidence_revision,algorithm_revision,input_json,status,
                 attempts,attempt_limit,lease_token,lease_until,last_failure_code,last_failure_at,failed_at)
            VALUES (?,?,?,?,?,CAST(? AS jsonb),?,?,?,CAST(? AS uuid),CAST(? AS timestamptz),?,
                CASE WHEN ?='FAILED' THEN clock_timestamp() ELSE NULL END,
                CASE WHEN ?='FAILED' THEN clock_timestamp() ELSE NULL END)
            ON CONFLICT(generation_key) DO UPDATE SET status=excluded.status,attempts=excluded.attempts,
                attempt_limit=excluded.attempt_limit,lease_token=excluded.lease_token,lease_until=excluded.lease_until,
                last_failure_code=excluded.last_failure_code,last_failure_at=excluded.last_failure_at,failed_at=excluded.failed_at
            """).params(RouteDisplayGeneration.key(digest,evidence,algorithm,g.shape().sourceManifestId()),
                g.routeId(),digest,evidence,algorithm,json.writeValueAsString(g.withDisplay(null)),status,attempts,limit,
                lease==null?null:UUID.randomUUID().toString(),lease,status.equals("FAILED")?"EXECUTION_FAILED":null,status,status).update();
    }

    @Test
    void DB시각으로_마지막lease와_시도예산을_판정하고_두종료후에만_표시를_보류한다() throws Exception {
        var g=current();jdbc.sql("TRUNCATE route_display_generation_job CASCADE").update();
        var mvc=MockMvcBuilders.standaloneSetup(new RouteGeometryController(service)).build();String path="/api/v2/routes/"+ID.value()+"/geometry";
        putGenerationJob(g,RouteDisplayGeneration.SOURCE_REVISION,"FAILED",1,5,null);
        for (String[] test:List.of(new String[]{"PENDING","4",null,"GENERATION_PENDING"},
                new String[]{"PENDING","5",null,"DISPLAY_WITHHELD"},
                new String[]{"SUPERSEDED","4",null,"GENERATION_PENDING"},
                new String[]{"SUPERSEDED","5",null,"DISPLAY_WITHHELD"},
                new String[]{"RUNNING","5","2099-01-01T00:00:00Z","GENERATION_PENDING"},
                new String[]{"RUNNING","5","2000-01-01T00:00:00Z","DISPLAY_WITHHELD"},
                new String[]{"RUNNING","4","2000-01-01T00:00:00Z","GENERATION_PENDING"},
                new String[]{"FAILED","5",null,"DISPLAY_WITHHELD"},
                new String[]{"COMPLETED","1",null,"DISPLAY_WITHHELD"})) {
            putGenerationJob(g,AutomaticRouteDisplay.REVISION,test[0],Integer.parseInt(test[1]),5,test[2]);
            mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.reason").value(test[3]))
                    .andExpect(jsonPath("$.points").isEmpty()).andExpect(jsonPath("$.paths").isEmpty())
                    .andExpect(header().string("Cache-Control","no-store")).andExpect(header().doesNotExist("ETag"));
        }
    }

    @Test
    void 이전입력의_종료작업은_새입력의_생성대기를_막지않는다() throws Exception {
        var g=current();jdbc.sql("TRUNCATE route_display_generation_job CASCADE").update();
        putGenerationJob(g,AutomaticRouteDisplay.REVISION,"FAILED",5,5,null);
        putGenerationJob(g,RouteDisplayGeneration.SOURCE_REVISION,"FAILED",5,5,null);
        var before=current();
        jdbc.sql("UPDATE route_stop_location_history SET x=x+0.00001 WHERE stop_order=1").update();
        var after=current();
        assertThat(before.display().quality().issues()).extracting(i->i.code()).containsExactly("GENERATION_FAILED");
        assertThat(after.display().quality().issues()).extracting(i->i.code()).containsExactly("GENERATION_PENDING");
        assertThat(after.revision()).isNotEqualTo(before.revision());
    }

    @Test void 자료복구_소진은_현재버전에서만_핀_응답으로_반영된다() throws Exception {
        var g=current();jdbc.sql("DELETE FROM route_geometry").update();
        jdbc.sql("INSERT INTO route_geometry_recovery(route_version_id,route_id,status,attempts) SELECT ?,id,'PENDING',0 FROM route WHERE public_route_id=?")
            .params(g.referenceVersionId(),ID.value()).update();
        var mvc=MockMvcBuilders.standaloneSetup(new RouteGeometryController(service)).build();
        String path="/api/v2/routes/"+ID.value()+"/geometry";
        mvc.perform(get(path)).andExpect(jsonPath("$.reason").value("NOT_COLLECTED"));
        jdbc.sql("UPDATE route_geometry_recovery SET status='EXHAUSTED',attempts=5").update();
        mvc.perform(get(path).header("If-None-Match","old-ready"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(header().doesNotExist("ETag")).andExpect(jsonPath("$.reason").value("DISPLAY_WITHHELD"))
            .andExpect(jsonPath("$.points").isEmpty()).andExpect(jsonPath("$.paths").isEmpty())
            .andExpect(jsonPath("$.busStops[0].point.x").value(127.0));
        assertThat(displays.findSourceRecovery(ID.value(),g.referenceVersionId()+1000)).isEmpty();
    }
    @Test void 실제작업상태는_첫시도와_오류후_재시도를_구별한다() {
        var g=current();String digest=RouteDisplayGeneration.input(g).digest(),evidence=g.shape().evidence().revision();
        String key=RouteDisplayGeneration.key(digest,evidence,AutomaticRouteDisplay.REVISION);
        jdbc.sql("UPDATE route_display_generation_job SET status='PENDING',attempts=0 WHERE generation_key=?").param(key).update();
        assertThat(displays.findGenerationStates(ID.value(),digest,evidence,List.of(key)).get(key).initialAttempt()).isTrue();
        jdbc.sql("UPDATE route_display_generation_job SET attempts=1 WHERE generation_key=?").param(key).update();
        assertThat(displays.findGenerationStates(ID.value(),digest,evidence,List.of(key)).get(key).initialAttempt()).isFalse();
        jdbc.sql("UPDATE route_display_generation_job SET status='RUNNING',lease_token=?,lease_until=clock_timestamp()+interval '1 minute' WHERE generation_key=?")
            .params(UUID.randomUUID(),key).update();
        assertThat(displays.findGenerationStates(ID.value(),digest,evidence,List.of(key)).get(key).initialAttempt()).isTrue();
        jdbc.sql("UPDATE route_display_generation_job SET lease_until=clock_timestamp()-interval '1 second' WHERE generation_key=?").param(key).update();
        var expired=displays.findGenerationStates(ID.value(),digest,evidence,List.of(key)).get(key);
        assertThat(expired.canProgress()).isTrue();assertThat(expired.initialAttempt()).isFalse();
    }


    private final io.micrometer.core.instrument.simple.SimpleMeterRegistry meters =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    private org.springframework.test.web.servlet.MockMvc plain() {
        return MockMvcBuilders.standaloneSetup(new RouteGeometryController(service)).build();
    }
    private org.springframework.test.web.servlet.MockMvc cachedMvc;

    private org.springframework.test.web.servlet.MockMvc cached() {
        if (cachedMvc == null) {
            cachedMvc = MockMvcBuilders.standaloneSetup(new RouteGeometryController(service,
                    new com.gustler.backend.api.route.controller.RouteGeometryResponseCache(
                            org.springframework.util.unit.DataSize.ofMegabytes(32), java.time.Duration.ofMinutes(10),
                            java.time.Duration.ofSeconds(1), meters))).build();
        }
        return cachedMvc;
    }

    private double cacheHits() {
        return meters.counter("salmonbus.route.geometry.cache", "result", "hit").count();
    }

    /** 캐시를 거친 응답을 먼저 받고, 캐시 없는 응답과 상태·본문·ETag·Cache-Control이 같은지 본다. 응답 status 필드를 준다. */
    private String sameAsUncached(String ifNoneMatch) throws Exception {
        String path = "/api/v2/routes/" + ID.value() + "/geometry";
        var cachedRequest = get(path);
        var plainRequest = get(path);
        if (ifNoneMatch != null) {
            cachedRequest.header("If-None-Match", ifNoneMatch);
            plainRequest.header("If-None-Match", ifNoneMatch);
        }
        var actual = cached().perform(cachedRequest).andReturn().getResponse();
        var expected = plain().perform(plainRequest).andReturn().getResponse();
        assertThat(actual.getStatus()).isEqualTo(expected.getStatus());
        assertThat(actual.getContentAsString()).isEqualTo(expected.getContentAsString());
        assertThat(actual.getHeader("ETag")).isEqualTo(expected.getHeader("ETag"));
        assertThat(actual.getHeader("Cache-Control")).isEqualTo(expected.getHeader("Cache-Control"));
        return expected.getContentAsString().isEmpty() ? null : json.readTree(expected.getContentAsString()).get("status").asText();
    }

    private String sameAsUncached() throws Exception {
        return sameAsUncached(null);
    }

    @Test
    void 응답캐시는_노선기본정보_정류장좌표_수동형상_변경을_놓치지_않는다() throws Exception {
        assertThat(sameAsUncached()).isEqualTo("READY");
        double hits = cacheHits();
        assertThat(sameAsUncached()).isEqualTo("READY");
        assertThat(cacheHits()).isEqualTo(hits + 1);
        String etag = cached().perform(get("/api/v2/routes/" + ID.value() + "/geometry")).andReturn().getResponse().getHeader("ETag");
        assertThat(sameAsUncached(etag)).isNull();

        jdbc.sql("UPDATE route SET display_name='새 이름'").update();
        assertThat(sameAsUncached(etag)).isEqualTo("READY");

        jdbc.sql("INSERT INTO route_stop_location_history(route_version_id,stop_order,stop_id,observed_at,x,y)"
                + " VALUES (?,2,'206000638','2026-10-15T00:00:00Z',127.0101,37.01)")
                .param(current().referenceVersionId()).update();
        sameAsUncached();
        installAutomatic(current());
        assertThat(sameAsUncached()).isEqualTo("READY");

        install(current(), 0.0001);
        assertThat(sameAsUncached()).isEqualTo("READY");
        hits = cacheHits();
        sameAsUncached();
        assertThat(cacheHits()).isEqualTo(hits + 1);
        var shifted = json.writeValueAsString(manual(RouteDisplaySelector.input(current()), 0.0002));
        jdbc.sql("UPDATE route_display_geometry SET document=CAST(? AS jsonb)").param(shifted).update();
        assertThat(sameAsUncached()).isEqualTo("READY");
        jdbc.sql("UPDATE route_display_geometry SET document='{}'::jsonb").update();
        assertThat(sameAsUncached()).isEqualTo("READY");
        jdbc.sql("UPDATE route_display_geometry SET disabled_at=CURRENT_TIMESTAMP").update();
        assertThat(sameAsUncached()).isEqualTo("READY");
    }

    @Test
    void 응답캐시는_같은_생성키로_다시_넣은_산출물과_작업상태_형상변경을_놓치지_않는다() throws Exception {
        RouteGeometry g = current();
        String digest = RouteDisplaySelector.input(g).digest();
        String improved = json.writeValueAsString(AutomaticRouteDisplay.build(g, digest));
        installArtifact(g, RouteDisplayGeneration.SOURCE_REVISION,
                json.writeValueAsString(AutomaticRouteDisplay.sourceOnly(g, digest)), null);
        assertThat(sameAsUncached()).isEqualTo("READY");

        jdbc.sql("TRUNCATE route_display_automatic_artifact").update();
        installArtifact(g, RouteDisplayGeneration.SOURCE_REVISION,
                json.writeValueAsString(AutomaticRouteDisplay.sourceOnly(g, digest)), null);
        installArtifact(g, AutomaticRouteDisplay.REVISION, improved.replace("\"revision\"", "\"revision \""),
                RouteDisplayGeneration.sha256(improved));
        assertThat(sameAsUncached()).isEqualTo("READY");

        putGenerationJob(g, AutomaticRouteDisplay.REVISION, "RUNNING", 1, 5, "2099-01-01T00:00:00Z");
        sameAsUncached();
        putGenerationJob(g, AutomaticRouteDisplay.REVISION, "FAILED", 5, 5, null);
        assertThat(sameAsUncached()).isEqualTo("READY");

        jdbc.sql("UPDATE route_geometry SET evidence='{}'::jsonb").update();
        sameAsUncached();
        jdbc.sql("UPDATE route_geometry SET points='[{\"x\":127,\"y\":37},{\"x\":127.01001,\"y\":37.01},{\"x\":127,\"y\":37}]'::jsonb").update();
        sameAsUncached();
        installAutomatic(current());
        assertThat(sameAsUncached()).isEqualTo("READY");
        jdbc.sql("DELETE FROM route_geometry").update();
        assertThat(sameAsUncached()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void 저장자료_식별값은_본문만_바꾼_수동형상과_다시넣은_산출물을_구별한다() {
        RouteGeometry g = current();
        install(g, 0.0001);
        String digest = RouteDisplaySelector.input(g).digest();
        String evidence = g.shape().evidence().revision();
        List<String> keys = List.of(RouteDisplayGeneration.key(digest, evidence, AutomaticRouteDisplay.REVISION));
        var before = displays.findStoredInputs(ID.value(), g.referenceVersionId(), digest, evidence, null, keys).orElseThrow().inputs();
        assertThat(before.manual()).isNotNull();
        assertThat(before.artifacts()).hasSize(1);
        assertThat(displays.findStoredInputs(ID.value(), g.referenceVersionId(), digest, evidence, null, keys)
                .orElseThrow().inputs()).isEqualTo(before);

        jdbc.sql("UPDATE route_display_geometry SET document='{}'::jsonb").update();
        var manualChanged = displays.findStoredInputs(ID.value(), g.referenceVersionId(), digest, evidence, null, keys)
                .orElseThrow().inputs();
        assertThat(manualChanged.manual().contentDigest()).isEqualTo(before.manual().contentDigest());
        assertThat(manualChanged.manual()).isNotEqualTo(before.manual());

        jdbc.sql("TRUNCATE route_display_automatic_artifact").update();
        installAutomatic(g);
        var artifactChanged = displays.findStoredInputs(ID.value(), g.referenceVersionId(), digest, evidence, null, keys)
                .orElseThrow().inputs();
        assertThat(artifactChanged.artifacts().getFirst().documentSha256())
                .isEqualTo(before.artifacts().getFirst().documentSha256());
        assertThat(artifactChanged.artifacts()).isNotEqualTo(before.artifacts());
        assertThat(artifactChanged.generationStates()).isEqualTo(before.generationStates());
    }

    @Autowired javax.sql.DataSource dataSource;

    /** 연결마다 만든 SQL 문 수를 센다. */
    private static javax.sql.DataSource counting(javax.sql.DataSource target, java.util.concurrent.atomic.AtomicInteger statements) {
        return (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(RouteDisplayLifecycleTest.class.getClassLoader(),
                new Class<?>[]{javax.sql.DataSource.class}, (proxy, method, args) -> {
                    Object result = invoke(method, target, args);
                    if (!(result instanceof java.sql.Connection connection)) return result;
                    return java.lang.reflect.Proxy.newProxyInstance(RouteDisplayLifecycleTest.class.getClassLoader(),
                            new Class<?>[]{java.sql.Connection.class}, (inner, call, callArgs) -> {
                                if (call.getName().equals("prepareStatement") || call.getName().equals("createStatement")
                                        || call.getName().equals("prepareCall")) statements.incrementAndGet();
                                return invoke(call, connection, callArgs);
                            });
                });
    }

    private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    /**
     * 캐시에 넣지 않는 응답을 포함해 미적중 경로의 SQL 문 수를 예전 조립(저장 자료 식별값을 읽지 않고 문서·상태를 하나씩 읽음)과 실제 DB로 비교한다.
     * 응답도 예전 조립과 같아야 한다.
     */
    private void assertMissPathNoMoreStatements(String expectedStatus) throws Exception {
        var statements = new java.util.concurrent.atomic.AtomicInteger();
        var client = JdbcClient.create(counting(dataSource, statements));
        var sources = new JdbcRouteGeometryQueryRepository(client, json);
        var stored = new JdbcRouteDisplayRepository(client);
        RouteDisplayRepository legacyReads = new RouteDisplayRepository() {
            @Override public Optional<Manual> findManual(String routeId, String digest) { return stored.findManual(routeId, digest); }
            @Override public Optional<Automatic> findAutomatic(String key, String routeId, String digest, String evidence,
                    String algorithm) { return stored.findAutomatic(key, routeId, digest, evidence, algorithm); }
            @Override public Map<String, GenerationState> findGenerationStates(String routeId, String digest,
                    String evidence, List<String> keys) { return stored.findGenerationStates(routeId, digest, evidence, keys); }
            @Override public Optional<GenerationState> findSourceRecovery(String routeId, final long versionId) {
                return stored.findSourceRecovery(routeId, versionId); }
            @Override public Optional<SourceManifest> findSourceManifest(String id) { return stored.findSourceManifest(id); }
        };
        var legacy = new RouteGeometryQueryService(sources, Optional.of(new RouteDisplaySelector(legacyReads, json, true)));
        var current = new RouteGeometryQueryService(sources, Optional.of(new RouteDisplaySelector(stored, json, true)));

        RouteGeometry expected = legacy.get(ID, null);
        final int legacyStatements = statements.getAndSet(0);
        RouteGeometry actual = current.respond(ID, null, new RouteGeometryResponder<RouteGeometry>() {
            @Override public Optional<RouteGeometry> reuse(RouteGeometryFingerprint fingerprint) { return Optional.empty(); }
            @Override public RouteGeometry create(RouteGeometry geometry, RouteGeometryFingerprint fingerprint) { return geometry; }
        });
        final int missStatements = statements.get();

        assertThat(actual).isEqualTo(expected);
        assertThat(com.gustler.backend.api.route.dto.RouteGeometryResponse.from(actual, false).status()).isEqualTo(expectedStatus);
        assertThat(missStatements).isLessThanOrEqualTo(legacyStatements);
    }

    @Test
    void 미적중_경로는_실제_DB에서_예전_조립보다_SQL문이_많지_않다() throws Exception {
        assertMissPathNoMoreStatements("READY");

        install(current(), 0.0001);
        assertMissPathNoMoreStatements("READY");

        jdbc.sql("UPDATE route_display_geometry SET disabled_at=CURRENT_TIMESTAMP").update();
        jdbc.sql("TRUNCATE route_display_automatic_artifact").update();
        assertMissPathNoMoreStatements("UNAVAILABLE");

        putGenerationJob(current(), AutomaticRouteDisplay.REVISION, "FAILED", 5, 5, null);
        putGenerationJob(current(), RouteDisplayGeneration.SOURCE_REVISION, "FAILED", 5, 5, null);
        assertMissPathNoMoreStatements("UNAVAILABLE");

        jdbc.sql("DELETE FROM route_geometry").update();
        assertMissPathNoMoreStatements("UNAVAILABLE");
    }
}
