package com.gustler.backend.api.route.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.*;

import com.gustler.backend.routegeometry.api.RouteDisplayDbSamples;
import com.gustler.backend.routegeometry.api.RouteDisplayInput;
import com.gustler.backend.routegeometry.api.RouteDisplaySamples;
import com.gustler.backend.routegeometry.api.RouteInputProvenance;
import com.gustler.backend.routegeometry.api.RouteShapeEvidence;
import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeneration;
import com.gustler.backend.support.PostgresTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = JdbcRouteSourceManifestReadTest.Config.class)
class JdbcRouteSourceManifestReadTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({PostgresTestContainer.class, JdbcRouteDisplayRepository.class})
    static class Config {}

    @Autowired JdbcClient jdbc;
    @Autowired JdbcRouteDisplayRepository repository;
    final ObjectMapper json = new ObjectMapper();
    long routeId;
    long versionId;
    String acquisition;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE route CASCADE").update();
        jdbc.sql("TRUNCATE route_source_manifest CASCADE").update();
        versionId = RouteDisplayDbSamples.seed(jdbc,"204000057");
        routeId = jdbc.sql("SELECT route_id FROM route_version WHERE id=?").param(versionId).query(Long.class).single();
        acquisition = acquisition("first");
    }

    String acquisition(String marker) {
        // This read test exercises the sealed input row and FK relationships, not acquisition publication.
        String document = json.writeValueAsString(java.util.Map.of("fixture",marker));
        String hash = RouteDisplayGeneration.sha256(document);
        jdbc.sql("INSERT INTO route_source_manifest(manifest_id,purpose,document) VALUES (?,'TEST_FIXTURE',?)")
            .params(hash,document).update();
        return hash;
    }

    String insert(String mismatch) {
        var input = RouteDisplaySamples.input();
        if (mismatch.equals("public_route_id")) {
            input = new RouteDisplayInput("204000058",input.turnSequence(),input.points(),input.visits());
        }
        var evidence = RouteShapeEvidence.EMPTY;
        var provenance = new RouteInputProvenance("route-input-manifest-v1",routeId,versionId,
            acquisition,input.digest(),evidence.revision(),input,evidence);
        String document = json.writeValueAsString(provenance);
        String hash = RouteDisplayGeneration.sha256(document);
        jdbc.sql("""
            INSERT INTO route_input_manifest(manifest_id,route_id,reference_version_id,input_digest,
                evidence_revision,acquisition_manifest_id,document) VALUES (?,?,?,?,?,?,?)
            """).params(hash,routeId,versionId,
                mismatch.equals("input_digest") ? "0".repeat(64) : input.digest(),
                mismatch.equals("evidence_revision") ? "different-evidence" : evidence.revision(),
                mismatch.equals("acquisition_manifest_id") ? acquisition("other") : acquisition,
                document).update();
        return hash;
    }

    @Test
    void 밀봉문서와_행메타데이터가_일치하는_근거만_읽는다() {
        String id = insert("none");
        var manifest = repository.findSourceManifest(id).orElseThrow();
        assertThat(manifest.inputDigest()).isEqualTo(RouteDisplaySamples.input().digest());
        assertThat(manifest.evidenceRevision()).isEqualTo(RouteShapeEvidence.EMPTY.revision());
        assertThat(manifest.referenceVersionId()).isEqualTo(versionId);
    }

    @ParameterizedTest
    @ValueSource(strings={"input_digest","evidence_revision","acquisition_manifest_id","public_route_id"})
    void 문서해시와_FK가_유효해도_행과_밀봉내용이_다르면_거부한다(String mismatch) {
        String id = insert(mismatch);
        assertThatThrownBy(() -> repository.findSourceManifest(id))
            .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
            .hasMessageContaining("sealed document");
    }

    @Test
    void 없는_수집근거는_임의의_기존근거로_대체하지않는다() {
        insert("none");
        assertThat(repository.findSourceManifest("f".repeat(64))).isEmpty();
    }
}
