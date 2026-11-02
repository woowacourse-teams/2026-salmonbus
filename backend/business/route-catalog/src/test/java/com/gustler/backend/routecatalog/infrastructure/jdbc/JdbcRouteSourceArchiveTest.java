package com.gustler.backend.routecatalog.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.*;

import com.gustler.backend.routecatalog.domain.geometry.RouteSourceArchive;
import com.gustler.backend.routegeometry.api.RouteDisplayInput;
import com.gustler.backend.routegeometry.api.RouteShapeEvidence;
import com.gustler.backend.routegeometry.api.RouteSourceReceipt;
import com.gustler.backend.routegeometry.api.RouteSourceReceipt.CoordinateSystem;
import com.gustler.backend.routegeometry.api.RouteSourceReceipt.Status;
import com.gustler.backend.support.PostgresTestContainer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = JdbcRouteSourceArchiveTest.Config.class)
class JdbcRouteSourceArchiveTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({PostgresTestContainer.class, JdbcRouteSourceArchive.class})
    static class Config {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }
    @Autowired RouteSourceArchive archive;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    private static final Instant TIME = Instant.parse("2026-11-02T00:00:00.123456789Z");
    private static final byte[] BODY = "{\n  \"name\": \"정류장\", \"x\": 127.1000\n}\n".getBytes(StandardCharsets.UTF_8);
    private long routeId;
    private long versionId;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE route,route_source_blob,route_source_receipt,route_source_manifest CASCADE").update();
        routeId = jdbc.sql("""
            INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)
            VALUES ('204000057','GBIS','204000057','3330','A','B') RETURNING id
            """).query(Long.class).single();
        versionId = jdbc.sql("INSERT INTO route_version(route_id,content_digest,valid_from) VALUES (?,?,'2026-11-01T00:00:00Z') RETURNING id")
            .params(routeId, "a".repeat(64)).query(Long.class).single();
    }

    private RouteSourceReceipt success(String source, String operation, String scope, Instant at) {
        return new RouteSourceReceipt(source, operation, scope, Status.SUCCESS, 200, at,
            "20261102", CoordinateSystem.WGS84_ASSUMED, BODY);
    }
    private String capture() {
        return archive.record("ROUTE_SHAPE", List.of(success("GBIS_API", "SHAPE", "204000057", TIME)), List.of());
    }
    private RouteDisplayInput input() {
        return new RouteDisplayInput("204000057", null,
            List.of(new RouteDisplayInput.Point(127,37), new RouteDisplayInput.Point(127.1,37.1)),
            List.of(new RouteDisplayInput.Visit("206000001",1,"UP",true,new RouteDisplayInput.Point(127,37))));
    }
    private int count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }

    @Test
    void original_bytes_and_precise_acquisition_metadata_round_trip() {
        var receipt = success("GBIS_API", "SHAPE", "204000057", TIME);
        String id = archive.record("ROUTE_SHAPE", List.of(receipt), List.of());
        var restored = archive.restore(id);
        assertThat(restored.manifestId()).isEqualTo(id);
        assertThat(restored.purpose()).isEqualTo("ROUTE_SHAPE");
        assertThat(restored.receipts()).containsExactly(receipt);
        assertThat(restored.receipts().getFirst().body()).containsExactly(BODY);
        assertThat(restored.receipts().getFirst().acquiredAt()).isEqualTo(TIME);
        assertThat(RouteSourceReceipt.sha256(restored.document().getBytes(StandardCharsets.UTF_8))).isEqualTo(id);
    }

    @Test
    void repeated_capture_deduplicates_bytes_and_exact_receipts() {
        String first = capture();
        assertThat(capture()).isEqualTo(first);
        archive.record("ROUTE_SHAPE", List.of(success("GBIS_API", "SHAPE", "204000057", TIME.plusSeconds(1))), List.of());
        assertThat(count("route_source_blob")).isEqualTo(1);
        assertThat(count("route_source_receipt")).isEqualTo(2);
        assertThat(count("route_source_manifest")).isEqualTo(2);
    }

    @Test
    void shared_bms_capture_is_referenced_without_copying_the_body() {
        String shared = archive.record("BMS_SHARED", List.of(success("BMS", "LINKS", "SHARED", TIME)), List.of());
        var receipt = success("GBIS_API", "SHAPE", "204000057", TIME);
        String first = archive.record("ROUTE_SHAPE", List.of(receipt), List.of(shared));
        String second = archive.record("ROUTE_SHAPE", List.of(success("GBIS_API", "SHAPE", "204000058", TIME)), List.of(shared));
        assertThat(archive.restore(first).reusedManifestIds()).containsExactly(shared);
        assertThat(archive.restore(second).reusedManifestIds()).containsExactly(shared);
        assertThat(archive.restore(shared).receipts().getFirst().body()).containsExactly(BODY);
        assertThat(count("route_source_blob")).isEqualTo(1);
        assertThat(count("route_source_manifest_reuse")).isEqualTo(2);
    }

    @Test
    void directly_constructed_success_receipt_cannot_persist_credential_echoes() {
        for (String response : List.of(
                "{\"serviceKey\":\"test-credential-echo\"}",
                "{\"message\":\"https://example.invalid/api?serviceKey=test-credential-echo\"}")) {
            var unsafe = new RouteSourceReceipt("GBIS_API", "SHAPE", "204000057", Status.SUCCESS,
                200, TIME, "UNKNOWN", CoordinateSystem.UNKNOWN, response.getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() -> archive.record("ROUTE_SHAPE",
                List.of(success("GBIS_API", "SHAPE", "204000057", TIME), unsafe), List.of()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Archive response failed retention screening")
                .hasMessageNotContaining("test-credential-echo");
            assertThat(count("route_source_blob")).isZero();
            assertThat(count("route_source_receipt")).isZero();
            assertThat(count("route_source_manifest")).isZero();
        }
    }

    @Test
    void failed_sources_preserve_status_without_response_bytes() {
        var failure = new RouteSourceReceipt("GBIS_WEB", "SHAPE", "204000057", Status.HTTP_ERROR,
            503,TIME,"UNKNOWN",CoordinateSystem.UNKNOWN,null);
        String id = archive.record("ROUTE_SHAPE", List.of(success("GBIS_API","SHAPE","204000057",TIME),failure),List.of());
        assertThat(archive.restore(id).receipts().get(1)).isEqualTo(failure);
        assertThat(archive.restore(id).receipts().get(1).body()).isNull();
        assertThat(count("route_source_blob")).isEqualTo(1);
    }

    @Test
    void binding_seals_exact_normalized_input_evidence_and_acquisition_identity() {
        String acquisition = capture();
        String id = archive.bind(routeId,versionId,acquisition,input(),RouteShapeEvidence.EMPTY);
        assertThat(archive.bind(routeId,versionId,acquisition,input(),RouteShapeEvidence.EMPTY)).isEqualTo(id);
        String document = jdbc.sql("SELECT document FROM route_input_manifest WHERE manifest_id=?")
            .param(id).query(String.class).single();
        var parsed = json.readValue(document,JdbcRouteSourceArchive.InputDocument.class);
        assertThat(parsed.input()).isEqualTo(input());
        assertThat(parsed.inputDigest()).isEqualTo(input().digest());
        assertThat(parsed.evidenceRevision()).isEqualTo(RouteShapeEvidence.EMPTY.revision());
        assertThat(parsed.acquisitionManifestId()).isEqualTo(acquisition);
        assertThat(RouteSourceReceipt.sha256(document.getBytes(StandardCharsets.UTF_8))).isEqualTo(id);
        assertThatThrownBy(() -> archive.bind(routeId,versionId+100,acquisition,input(),RouteShapeEvidence.EMPTY))
            .isInstanceOf(RuntimeException.class);
        assertThat(count("route_input_manifest")).isEqualTo(1);
    }

    @Test
    void archives_and_manifest_relations_are_immutable() {
        String id = capture();
        archive.bind(routeId,versionId,id,input(),RouteShapeEvidence.EMPTY);
        for (String table : List.of("route_source_blob","route_source_receipt","route_source_manifest",
                "route_source_manifest_receipt","route_input_manifest")) {
            assertThatThrownBy(() -> jdbc.sql("DELETE FROM " + table).update()).isInstanceOf(DataAccessException.class);
        }
        assertThatThrownBy(() -> jdbc.sql("UPDATE route_source_blob SET body=body").update())
            .isInstanceOf(DataAccessException.class);
        assertThat(archive.restore(id).receipts().getFirst().body()).containsExactly(BODY);
    }

    @Test
    void database_rejects_incorrect_byte_and_document_hashes() {
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO route_source_blob(body_sha256,body) VALUES (?,?)")
            .params("f".repeat(64),BODY).update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO route_source_manifest(manifest_id,purpose,document) VALUES (?,'TEST','{}')")
            .param("e".repeat(64)).update()).isInstanceOf(DataAccessException.class);
        assertThat(count("route_source_blob")).isZero();
        assertThat(count("route_source_manifest")).isZero();
    }

    @Test
    void dangling_reuse_is_rejected_before_a_manifest_is_published() {
        assertThatThrownBy(() -> archive.record("ROUTE_SHAPE",List.of(),List.of("f".repeat(64))))
            .isInstanceOf(RuntimeException.class);
        assertThat(count("route_source_manifest")).isZero();
        String id = capture();
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO route_source_manifest_reuse(manifest_id,ordinal,reused_manifest_id) VALUES (?,0,?)")
            .params(id,"f".repeat(64)).update()).isInstanceOf(DataAccessException.class);
    }

    @Test
    void recursive_restore_has_a_bounded_depth() {
        String id = capture();
        for (int i = 0; i < 32; i++) id = archive.record("SHARED_CHAIN",List.of(),List.of(id));
        assertThat(archive.restore(id).purpose()).isEqualTo("SHARED_CHAIN");
        String deepest = id;
        assertThatThrownBy(() -> archive.record("SHARED_CHAIN",List.of(),List.of(deepest)))
            .isInstanceOf(RuntimeException.class);
        assertThat(count("route_source_manifest")).isEqualTo(33);
    }
}
