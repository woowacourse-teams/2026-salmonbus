package com.gustler.backend.worker.routegeometry;

import static org.assertj.core.api.Assertions.*;

import com.gustler.backend.routegeometry.api.RouteDisplayDbSamples;
import com.gustler.backend.routegeometry.api.RouteInputProvenance;
import com.gustler.backend.routegeometry.api.RouteShapeEvidence;
import com.gustler.backend.routegeometry.engine.api.AutomaticRouteDisplay;
import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeneration;
import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeometry;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry;
import com.gustler.backend.support.PostgresTestContainer;
import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Sealed normalized fixture manifests verify provenance binding, not provider-response completeness. */
@SpringBootTest(classes=RouteSourceProvenanceGenerationTest.Config.class)
class RouteSourceProvenanceGenerationTest {
    @Configuration(proxyBeanMethods=false)
    @EnableAutoConfiguration
    @Import({PostgresTestContainer.class,JdbcRouteDisplayGenerationStore.class,RouteDisplaySourceReader.class})
    static class Config { @Bean ObjectMapper mapper() { return new ObjectMapper(); } }
    private static final String ROUTE="204000057";
    private static final Instant START=Instant.parse("2026-11-02T00:00:00Z");
    @Autowired JdbcClient jdbc;
    @Autowired JdbcRouteDisplayGenerationStore store;
    @Autowired RouteDisplaySourceReader source;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    private MutableClock clock;
    private RouteDisplayGenerationWorker worker;
    private long routeId;

    @BeforeEach void reset() {
        jdbc.sql("TRUNCATE route,route_source_manifest,route_source_receipt,route_source_blob CASCADE").update();
        jdbc.sql("TRUNCATE route_display_generation_job,route_display_automatic_artifact").update();
        RouteDisplayDbSamples.seed(jdbc,ROUTE);
        routeId=jdbc.sql("SELECT id FROM route WHERE public_route_id=?").param(ROUTE).query(Long.class).single();
        clock=new MutableClock(START);
        worker=new RouteDisplayGenerationWorker(store,source,json,transactions,Optional.of(clock));
    }
    private RouteGeometry current() {
        return new TransactionTemplate(transactions).execute(tx -> source.readLocked(ROUTE).orElseThrow());
    }
    private int artifacts() { return jdbc.sql("SELECT count(*) FROM route_display_automatic_artifact").query(Integer.class).single(); }
    private int jobs() { return jdbc.sql("SELECT count(*) FROM route_display_generation_job").query(Integer.class).single(); }
    private String acquisition(Instant acquiredAt) {
        String document=json.writeValueAsString(Map.of("format","test-acquisition-manifest-v1",
            "fixtureOnly",true,"rawResponsesCaptured",false,"acquiredAt",acquiredAt.toString()));
        String id=RouteDisplayGeneration.sha256(document);
        jdbc.sql("INSERT INTO route_source_manifest(manifest_id,purpose,document) VALUES (?,'TEST_FIXTURE',?) ON CONFLICT DO NOTHING")
            .params(id,document).update();
        return id;
    }
    private String capture(RouteGeometry geometry,Instant acquiredAt) {
        String acquisition=acquisition(acquiredAt);
        var input=RouteDisplayGeneration.input(geometry);
        var provenance=new RouteInputProvenance("route-input-manifest-v1",routeId,geometry.referenceVersionId(),
            acquisition,input.digest(),geometry.shape().evidence().revision(),input,geometry.shape().evidence());
        return insertManifest(json.writeValueAsString(provenance),provenance.inputDigest(),provenance.evidenceRevision(),
            acquisition,geometry.referenceVersionId());
    }
    private String insertManifest(String document,String inputDigest,String evidenceRevision,String acquisition,long version) {
        String id=RouteDisplayGeneration.sha256(document);
        jdbc.sql("""
            INSERT INTO route_input_manifest(manifest_id,route_id,reference_version_id,input_digest,evidence_revision,acquisition_manifest_id,document)
            VALUES (?,?,?,?,?,?,?)
            """).params(id,routeId,version,inputDigest,evidenceRevision,acquisition,document).update();
        jdbc.sql("UPDATE route_geometry SET source_manifest_id=? WHERE route_version_id=?").params(id,version).update();
        return id;
    }
    private String document(JdbcRouteDisplayGenerationStore.Job job) {
        var g=json.readValue(job.inputJson(),RouteGeometry.class);
        var display=job.algorithmRevision().equals(RouteDisplayGeneration.SOURCE_REVISION)
            ? AutomaticRouteDisplay.sourceOnly(g,job.inputDigest()) : AutomaticRouteDisplay.build(g,job.inputDigest());
        return json.writeValueAsString(display);
    }
    private void drain() { for(int i=0;i<8 && worker.runOne();i++) { } }

    @Test void sealed_normalized_manifest_is_preserved_by_both_jobs_and_artifacts() {
        String manifest=capture(current(),START);
        assertThat(current().shape().sourceManifestId()).isEqualTo(manifest);
        worker.discover(ROUTE);
        var snapshot=json.readValue(jdbc.sql("SELECT input_json::text FROM route_display_generation_job LIMIT 1")
            .query(String.class).single(),RouteGeometry.class);
        assertThat(snapshot.shape().sourceManifestId()).isEqualTo(manifest);
        assertThat(snapshot.shape().evidenceEvaluatedAt()).isEqualTo(START);
        assertThat(source.snapshotProvenanceMatches(snapshot,manifest)).isTrue();
        drain();
        assertThat(artifacts()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT source_manifest_id FROM route_display_generation_job").query(String.class).list())
            .containsExactlyInAnyOrder(manifest,manifest);
        assertThat(jdbc.sql("SELECT source_manifest_id FROM route_display_automatic_artifact").query(String.class).list())
            .containsExactlyInAnyOrder(manifest,manifest);
    }
    @Test void repeated_identical_acquisition_keeps_semantic_jobs_and_first_consumed_provenance() {
        String first=capture(current(),START);
        worker.discover(ROUTE);drain();
        var oldKeys=jdbc.sql("SELECT generation_key FROM route_display_generation_job ORDER BY generation_key").query(String.class).list();
        String next=capture(current(),START.plusSeconds(30));
        assertThat(next).isNotEqualTo(first);
        assertThat(current().shape().sourceManifestId()).isEqualTo(next);
        clock.now=START.plusSeconds(30);
        worker.discover(ROUTE);
        assertThat(worker.runOne()).isFalse();
        assertThat(jobs()).isEqualTo(2);assertThat(artifacts()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT generation_key FROM route_display_generation_job ORDER BY generation_key").query(String.class).list()).isEqualTo(oldKeys);
        assertThat(jdbc.sql("SELECT source_manifest_id FROM route_display_automatic_artifact").query(String.class).list())
            .containsExactlyInAnyOrder(first,first);
    }
    @Test void legacy_to_captured_input_changes_identity_once_without_rewriting_legacy_artifacts() {
        worker.discover(ROUTE);drain();
        var legacy=jdbc.sql("SELECT generation_key FROM route_display_generation_job ORDER BY generation_key").query(String.class).list();
        String captured=capture(current(),START);
        worker.discover(ROUTE);drain();
        assertThat(jobs()).isEqualTo(4);assertThat(artifacts()).isEqualTo(4);
        var capturedKeys=jdbc.sql("SELECT generation_key FROM route_display_automatic_artifact WHERE source_manifest_id=?")
            .param(captured).query(String.class).list();
        assertThat(capturedKeys).hasSize(2).doesNotContainAnyElementsOf(legacy);
        assertThat(jdbc.sql("SELECT count(*) FROM route_display_automatic_artifact WHERE source_manifest_id IS NULL")
            .query(Integer.class).single()).isEqualTo(2);
        worker.discover(ROUTE);assertThat(worker.runOne()).isFalse();
    }
    @Test void expired_reference_uses_sealed_original_evidence_at_snapshot_time_and_rejects_old_completion() {
        var evidence=new RouteShapeEvidence("GBIS_API",List.of(new RouteShapeEvidence.Source(
            "GG_BMS_NODES","AVAILABLE","a".repeat(64),START.plusSeconds(60))),List.of());
        jdbc.sql("UPDATE route_geometry SET evidence=CAST(? AS jsonb)").param(json.writeValueAsString(evidence)).update();
        String manifest=capture(current(),START);
        worker.discover(ROUTE);
        var old=store.claim(Duration.ofMinutes(5)).orElseThrow();String computed=document(old);
        clock.now=START.plusSeconds(61);
        assertThat(worker.publish(old,computed)).isFalse();assertThat(artifacts()).isZero();
        worker.discover(ROUTE);drain();
        assertThat(artifacts()).isEqualTo(2);
        var snapshots=jdbc.sql("SELECT input_json::text FROM route_display_generation_job WHERE status='COMPLETED'")
            .query(String.class).list();
        assertThat(snapshots).hasSize(2);
        for(String value:snapshots) {
            var snapshot=json.readValue(value,RouteGeometry.class);
            assertThat(snapshot.shape().evidenceEvaluatedAt()).isEqualTo(START.plusSeconds(61));
            assertThat(snapshot.shape().evidence().sources()).extracting(RouteShapeEvidence.Source::status).containsExactly("STALE");
            assertThat(source.snapshotProvenanceMatches(snapshot,manifest)).isTrue();
        }
        // Validation relies on the recorded evaluation time, not the verifier's later wall clock.
        clock.now=START.plusSeconds(3600);
        for(String value:snapshots) assertThat(source.snapshotProvenanceMatches(json.readValue(value,RouteGeometry.class),manifest)).isTrue();
        assertThat(current().shape().evidence().sources()).extracting(RouteShapeEvidence.Source::status).containsExactly("AVAILABLE");
        assertThat(jdbc.sql("SELECT source_manifest_id FROM route_display_automatic_artifact").query(String.class).list())
            .containsExactlyInAnyOrder(manifest,manifest);
    }
    @Test void changed_stops_before_shape_refresh_drop_old_binding_instead_of_mislabeling_new_jobs() {
        String old=capture(current(),START);
        jdbc.sql("UPDATE route_stop_location_history SET x=127.0101 WHERE stop_order=2").update();
        var current=current();
        assertThat(current.shape().sourceManifestId()).isNull();
        assertThat(current.shape().unboundSourceManifestId()).isEqualTo(old);
        assertThat(jdbc.sql("SELECT source_manifest_id FROM route_geometry").query(String.class).single()).isEqualTo(old);
        worker.discover(ROUTE);drain();
        assertThat(artifacts()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT input_json #>> '{shape,unboundSourceManifestId}' FROM route_display_generation_job").query(String.class).list()).containsOnly(old);
        assertThat(jdbc.sql("SELECT count(*) FROM route_display_generation_job WHERE source_manifest_id IS NULL").query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM route_display_automatic_artifact WHERE source_manifest_id IS NULL").query(Integer.class).single()).isEqualTo(2);
    }
    @ParameterizedTest @ValueSource(strings={"METADATA_DIGEST","DOCUMENT_INPUT"})
    void forged_provenance_is_rejected_before_generation(String mutation) {
        var geometry=current();String acquisition=acquisition(START);
        var input=RouteDisplayGeneration.input(geometry);
        var provenance=new RouteInputProvenance("route-input-manifest-v1",routeId,geometry.referenceVersionId(),
            acquisition,input.digest(),geometry.shape().evidence().revision(),input,geometry.shape().evidence());
        String document=json.writeValueAsString(provenance),columnDigest=input.digest();
        if(mutation.equals("METADATA_DIGEST")) columnDigest="c".repeat(64);
        else {
            ObjectNode forged=(ObjectNode)json.readTree(document);
            ((ObjectNode)forged.at("/input/points/1")).put("x",127.02);
            document=json.writeValueAsString(forged);
        }
        insertManifest(document,columnDigest,provenance.evidenceRevision(),acquisition,geometry.referenceVersionId());
        assertThatThrownBy(() -> worker.discover(ROUTE)).isInstanceOf(RuntimeException.class);
        assertThat(jobs()).isZero();assertThat(artifacts()).isZero();
    }
    @Test void a_job_that_names_a_different_manifest_than_its_snapshot_is_rejected() {
        String first=capture(current(),START);
        var snapshot=RouteDisplayGeneration.at(current(),START);
        String second=capture(current(),START.plusSeconds(1));
        String digest=RouteDisplayGeneration.input(snapshot).digest(),evidence=snapshot.shape().evidence().revision();
        String key=RouteDisplayGeneration.key(digest,evidence,RouteDisplayGeneration.SOURCE_REVISION,second);
        store.enqueue(key,ROUTE,digest,evidence,RouteDisplayGeneration.SOURCE_REVISION,json.writeValueAsString(snapshot),second);
        assertThat(first).isNotEqualTo(second);
        assertThat(worker.runOne()).isTrue();
        assertThat(jdbc.sql("SELECT status FROM route_display_generation_job WHERE generation_key=?").param(key).query(String.class).single()).isEqualTo("FAILED");
        assertThat(artifacts()).isZero();
    }
    private static class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant value) { now=value; }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
