package com.gustler.backend.worker.routegeometry;

import static org.assertj.core.api.Assertions.*;

import com.gustler.backend.routegeometry.engine.api.AutomaticRouteDisplay;
import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeneration;
import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeometry;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry;
import com.gustler.backend.routegeometry.api.RouteDisplayDbSamples;
import com.gustler.backend.routegeometry.api.RouteShapeEvidence;
import com.gustler.backend.support.PostgresTestContainer;
import java.time.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

@SpringBootTest(classes = RouteDisplayGenerationWorkerTest.Config.class)
class RouteDisplayGenerationWorkerTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({PostgresTestContainer.class, JdbcRouteDisplayGenerationStore.class, RouteDisplaySourceReader.class})
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

    @BeforeEach void reset() {
        jdbc.sql("TRUNCATE route_display_generation_job,route_display_automatic_artifact").update();
        jdbc.sql("TRUNCATE route CASCADE").update();
        RouteDisplayDbSamples.seed(jdbc,ROUTE);
        clock=new MutableClock(START);
        worker=restartedWorker();
    }
    private RouteDisplayGenerationWorker restartedWorker() {
        return new RouteDisplayGenerationWorker(store,source,json,transactions,Optional.of(clock));
    }
    private RouteGeometry current() {
        return new TransactionTemplate(transactions).execute(tx -> source.readLocked(ROUTE).orElseThrow());
    }
    private String document(JdbcRouteDisplayGenerationStore.Job job) {
        var input=json.readValue(job.inputJson(),RouteGeometry.class);
        return json.writeValueAsString(job.algorithmRevision().equals(RouteDisplayGeneration.SOURCE_REVISION)
            ? AutomaticRouteDisplay.sourceOnly(input,job.inputDigest()) : AutomaticRouteDisplay.build(input,job.inputDigest()));
    }
    private int artifactCount() { return jdbc.sql("SELECT count(*) FROM route_display_automatic_artifact").query(Integer.class).single(); }
    private String state(String key) {
        return jdbc.sql("SELECT status FROM route_display_generation_job WHERE generation_key=?").param(key).query(String.class).single();
    }
    @Test void source_validation_precedes_full_generation_and_discovery_is_idempotent() {
        worker.discover(ROUTE);worker.discover(ROUTE);
        assertThat(jdbc.sql("SELECT count(*) FROM route_display_generation_job").query(Integer.class).single()).isEqualTo(2);
        assertThat(worker.runOne()).isTrue();
        assertThat(artifactCount()).isEqualTo(1);
        var first=jdbc.sql("SELECT algorithm_revision,document FROM route_display_automatic_artifact")
            .query((r,n) -> List.of(r.getString(1),r.getString(2))).single();
        assertThat(first.get(0)).isEqualTo(RouteDisplayGeneration.SOURCE_REVISION);
        var sourceDisplay=json.readValue(first.get(1),RouteDisplayGeometry.class);
        assertThat(sourceDisplay.quality().status()).isEqualTo(RouteDisplayGeometry.QualityStatus.SOURCE_ONLY);
        assertThat(sourceDisplay.connections()).hasSize(3);
        assertThat(worker.runOne()).isTrue();
        assertThat(artifactCount()).isEqualTo(2);
        var generated=json.readValue(jdbc.sql("SELECT document FROM route_display_automatic_artifact WHERE algorithm_revision=?")
            .param(AutomaticRouteDisplay.REVISION).query(String.class).single(),RouteDisplayGeometry.class);
        assertThat(generated.quality().status()).isEqualTo(RouteDisplayGeometry.QualityStatus.PASSED);
        assertThat(generated.connections()).hasSize(3);
        worker.discover(ROUTE);assertThat(worker.runOne()).isFalse();
    }
    @Test void changed_stop_coordinates_fence_an_already_computed_result() {
        worker.discover(ROUTE);
        var job=store.claim(Duration.ofMinutes(5)).orElseThrow();var computed=document(job);
        jdbc.sql("UPDATE route_stop_location_history SET x=x+0.0001 WHERE stop_order=2").update();
        assertThat(worker.publish(job,computed)).isFalse();
        assertThat(state(job.generationKey())).isEqualTo("SUPERSEDED");assertThat(artifactCount()).isZero();
        worker.discover(ROUTE);assertThat(worker.runOne()).isTrue();
        assertThat(jdbc.sql("SELECT input_digest FROM route_display_automatic_artifact").query(String.class).single())
            .isEqualTo(RouteDisplayGeneration.input(current()).digest()).isNotEqualTo(job.inputDigest());
    }
    @Test void source_A_to_B_to_A_revives_superseded_work_and_rejects_old_owner() {
        worker.discover(ROUTE);var original=store.claim(Duration.ofMinutes(5)).orElseThrow();var computed=document(original);
        jdbc.sql("UPDATE route_stop_location_history SET x=127.0101 WHERE stop_order=2").update();
        assertThat(worker.publish(original,computed)).isFalse();worker.discover(ROUTE);
        jdbc.sql("UPDATE route_stop_location_history SET x=127.01 WHERE stop_order=2").update();worker.discover(ROUTE);
        assertThat(state(original.generationKey())).isEqualTo("PENDING");
        for(int i=0;i<8 && worker.runOne();i++) { }
        assertThat(state(original.generationKey())).isEqualTo("COMPLETED");
        assertThat(jdbc.sql("SELECT count(*) FROM route_display_automatic_artifact WHERE input_digest=?")
            .param(original.inputDigest()).query(Integer.class).single()).isEqualTo(2);
        assertThat(worker.publish(original,computed)).isFalse();
    }
    @Test void reference_expiry_rejects_result_even_when_raw_input_is_unchanged() {
        var evidence=new RouteShapeEvidence("GBIS_API",List.of(new RouteShapeEvidence.Source(
            "GG_BMS_NODES","AVAILABLE","a".repeat(64),START.plusSeconds(60))),List.of());
        jdbc.sql("UPDATE route_geometry SET evidence=CAST(? AS jsonb)").param(json.writeValueAsString(evidence)).update();
        worker.discover(ROUTE);var old=store.claim(Duration.ofMinutes(5)).orElseThrow();var computed=document(old);
        clock.now=START.plusSeconds(61);assertThat(worker.publish(old,computed)).isFalse();assertThat(artifactCount()).isZero();
        worker.discover(ROUTE);assertThat(worker.runOne()).isTrue();
        var artifact=jdbc.sql("SELECT input_digest,evidence_revision,document FROM route_display_automatic_artifact")
            .query((r,n) -> List.of(r.getString(1),r.getString(2),r.getString(3))).single();
        assertThat(artifact.get(0)).isEqualTo(old.inputDigest());assertThat(artifact.get(1)).isNotEqualTo(old.evidenceRevision());
        assertThat(json.readValue(artifact.get(2),RouteDisplayGeometry.class).evidence()).extracting(RouteShapeEvidence.Source::status)
            .containsExactly("STALE");
    }
    private void renewReference(Instant until) {
        var evidence=new RouteShapeEvidence("GBIS_API",List.of(new RouteShapeEvidence.Source(
            "GG_BMS_NODES","AVAILABLE","a".repeat(64),until)),List.of());
        jdbc.sql("UPDATE route_geometry SET evidence=CAST(? AS jsonb)").param(json.writeValueAsString(evidence)).update();
    }
    @Test void renewed_reference_TTL_gets_new_jobs_even_after_both_old_artifacts_completed() {
        renewReference(START.plusSeconds(60));
        worker.discover(ROUTE);
        assertThat(worker.runOne()).isTrue();assertThat(worker.runOne()).isTrue();
        var oldKeys=jdbc.sql("SELECT generation_key FROM route_display_automatic_artifact ORDER BY generation_key")
            .query(String.class).list();
        var oldDigest=RouteDisplayGeneration.input(current()).digest();
        var oldRevision=current().shape().evidence().revision();
        assertThat(oldKeys).hasSize(2);
        renewReference(START.plusSeconds(120));
        assertThat(RouteDisplayGeneration.input(current()).digest()).isEqualTo(oldDigest);
        var renewedRevision=current().shape().evidence().revision();
        assertThat(renewedRevision).isNotEqualTo(oldRevision);
        worker.discover(ROUTE);
        assertThat(jdbc.sql("SELECT count(*) FROM route_display_generation_job WHERE status='PENDING'")
            .query(Integer.class).single()).isEqualTo(2);
        assertThat(worker.runOne()).isTrue();assertThat(worker.runOne()).isTrue();
        assertThat(artifactCount()).isEqualTo(4);
        var renewed=jdbc.sql("SELECT generation_key,document FROM route_display_automatic_artifact WHERE evidence_revision=?")
            .param(renewedRevision).query((r,n) -> List.of(r.getString(1),r.getString(2))).list();
        assertThat(renewed).hasSize(2);
        for(var artifact:renewed) {
            assertThat(oldKeys).doesNotContain(artifact.get(0));
            assertThat(json.readValue(artifact.get(1),RouteDisplayGeometry.class).evidence())
                .extracting(RouteShapeEvidence.Source::validUntil).containsExactly(START.plusSeconds(120));
        }
        // Existing evidence is immutable: TTL renewal publishes new documents, never edits old ones.
        var originals=jdbc.sql("SELECT document FROM route_display_automatic_artifact WHERE evidence_revision=?")
            .param(oldRevision).query(String.class).list();
        assertThat(originals).hasSize(2);
        for(var original:originals) assertThat(json.readValue(original,RouteDisplayGeometry.class).evidence())
            .extracting(RouteShapeEvidence.Source::validUntil).containsExactly(START.plusSeconds(60));
    }
    @Test void reference_TTL_renewal_rejects_an_in_flight_old_generation() {
        renewReference(START.plusSeconds(60));
        worker.discover(ROUTE);
        assertThat(worker.runOne()).isTrue();
        var old=store.claim(Duration.ofMinutes(5)).orElseThrow();
        assertThat(old.algorithmRevision()).isEqualTo(AutomaticRouteDisplay.REVISION);
        var computed=document(old);
        renewReference(START.plusSeconds(120));
        assertThat(worker.publish(old,computed)).isFalse();
        assertThat(state(old.generationKey())).isEqualTo("SUPERSEDED");
        assertThat(artifactCount()).isEqualTo(1);
        worker.discover(ROUTE);
        assertThat(worker.runOne()).isTrue();assertThat(worker.runOne()).isTrue();
        assertThat(artifactCount()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT count(*) FROM route_display_automatic_artifact WHERE generation_key=?")
            .param(old.generationKey()).query(Integer.class).single()).isZero();
    }
    @Test void recreated_worker_finishes_durable_jobs_without_rediscovery() {
        worker.discover(ROUTE);worker=restartedWorker();assertThat(worker.runOne()).isTrue();
        worker=restartedWorker();assertThat(worker.runOne()).isTrue();assertThat(artifactCount()).isEqualTo(2);
        assertThat(worker.runOne()).isFalse();
    }
    @Test void completion_does_not_overwrite_manual_registered_while_generation_was_running() {
        worker.discover(ROUTE);assertThat(worker.runOne()).isTrue();var job=store.claim(Duration.ofMinutes(5)).orElseThrow();
        assertThat(job.algorithmRevision()).isEqualTo(AutomaticRouteDisplay.REVISION);
        jdbc.sql("""
            INSERT INTO route_display_geometry(route_id,input_digest,content_digest,document)
            SELECT id,?,?,CAST(? AS jsonb) FROM route WHERE public_route_id=?
            """).params(job.inputDigest(),"c".repeat(64),"{\"reviewed\":true}",ROUTE).update();
        assertThat(worker.publish(job,document(job))).isTrue();
        assertThat(jdbc.sql("SELECT document::text FROM route_display_geometry").query(String.class).single()).contains("\"reviewed\": true");
        assertThat(jdbc.sql("SELECT disabled_at IS NULL FROM route_display_geometry").query(Boolean.class).single()).isTrue();
        assertThat(artifactCount()).isEqualTo(2);
    }
    @Test void invalid_source_is_withheld_instead_of_being_published_as_displayable_fallback() {
        jdbc.sql("UPDATE route_stop_location_history SET y=y+0.001 WHERE stop_order=1").update();
        worker.discover(ROUTE);assertThat(worker.runOne()).isTrue();
        var display=json.readValue(jdbc.sql("SELECT document FROM route_display_automatic_artifact").query(String.class).single(),RouteDisplayGeometry.class);
        assertThat(display.quality().status()).isEqualTo(RouteDisplayGeometry.QualityStatus.WITHHELD);
        assertThat(display.paths()).isNull();assertThat(display.connections()).isEmpty();
    }
    @Test void literal_null_supplementary_cache_is_marked_invalid_and_source_validation_still_completes() {
        jdbc.sql("UPDATE route_geometry SET evidence='null'::jsonb").update();
        worker.discover(ROUTE);
        assertThat(worker.runOne()).isTrue();
        assertThat(worker.runOne()).isTrue();
        assertThat(artifactCount()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM route_display_generation_job WHERE status='COMPLETED'")
            .query(Integer.class).single()).isEqualTo(2);
        var artifacts=jdbc.sql("SELECT algorithm_revision,document FROM route_display_automatic_artifact")
            .query((r,n) -> List.of(r.getString(1),r.getString(2))).list();
        for(var artifact:artifacts) {
            var display=json.readValue(artifact.get(1),RouteDisplayGeometry.class);
            assertThat(display.evidence()).extracting(RouteShapeEvidence.Source::status).containsExactly("INVALID");
            assertThat(display.evidence()).extracting(RouteShapeEvidence.Source::id).containsExactly("SUPPLEMENTARY_CACHE");
            assertThat(display.paths()).isNotNull();
            assertThat(display.connections()).hasSize(3);
            assertThat(display.quality().status()).isEqualTo(artifact.get(0).equals(RouteDisplayGeneration.SOURCE_REVISION)
                ? RouteDisplayGeometry.QualityStatus.SOURCE_ONLY : RouteDisplayGeometry.QualityStatus.PASSED);
        }
        assertThat(current().points()).hasSize(3);
    }
    private static class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now=now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
