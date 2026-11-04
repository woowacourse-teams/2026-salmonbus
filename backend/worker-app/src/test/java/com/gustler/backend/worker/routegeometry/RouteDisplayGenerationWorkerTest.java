package com.gustler.backend.worker.routegeometry;

import static org.assertj.core.api.Assertions.*;

import com.gustler.backend.routegeometry.engine.api.AutomaticRouteDisplay;
import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeneration;
import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeometry;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry;
import com.gustler.backend.routegeometry.api.RouteDisplayDbSamples;
import com.gustler.backend.routegeometry.api.RouteShapeEvidence;
import com.gustler.backend.routecatalog.api.RefreshRouteCatalog;
import com.gustler.backend.routecatalog.api.RefreshRouteGeometry;
import com.gustler.backend.routecatalog.api.RouteGeometryInputAvailable;
import com.gustler.backend.routecatalog.api.RouteReference;
import com.gustler.backend.routecatalog.api.RouteVersionChanged;
import com.gustler.backend.observations.api.ObservationRouteMismatchDetected;
import com.gustler.backend.worker.configuration.CollectionProperties;
import com.gustler.backend.worker.configuration.RouteGeometryProperties;
import com.gustler.backend.worker.scheduling.JdbcRouteRefreshStateRepository;
import com.gustler.backend.worker.scheduling.RouteChangeRefreshCoordinator;
import com.gustler.backend.support.PostgresTestContainer;
import java.time.*;
import java.util.List;
import java.util.Optional;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = RouteDisplayGenerationWorkerTest.Config.class,
    properties = {"route-geometry.generation.max-attempts=5", "route-geometry.enabled=true",
        "route-geometry.generation.enabled=true"})
class RouteDisplayGenerationWorkerTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({PostgresTestContainer.class, JdbcRouteDisplayGenerationStore.class, RouteDisplaySourceReader.class,
        JdbcRouteRefreshStateRepository.class, RouteChangeRefreshCoordinator.class,
        RouteDisplayGenerationWorker.class, RouteDisplayGenerationWakeup.class})
    static class Config {
        @Bean ObjectMapper mapper() { return new ObjectMapper(); }
        @Bean Tasks tasks() { return new Tasks(); }
        @Bean Executor routeGeometryExecutor(Tasks tasks) { return tasks.information::add; }
        @Bean Executor routeDisplayGenerationScheduler(Tasks tasks) { return tasks.generation::add; }
        @Bean CollectionProperties collectionProperties() { return new CollectionProperties(true,List.of(ROUTE)); }
        @Bean RouteGeometryProperties routeGeometryProperties() { return new RouteGeometryProperties(true,List.of(ROUTE)); }
        @Bean RefreshRouteCatalog catalogRefresher() { return org.mockito.Mockito.mock(RefreshRouteCatalog.class); }
        @Bean RefreshRouteGeometry geometryCollector() { return org.mockito.Mockito.mock(RefreshRouteGeometry.class); }
    }
    static class Tasks {
        final ArrayDeque<Runnable> information=new ArrayDeque<>();
        final ArrayDeque<Runnable> generation=new ArrayDeque<>();
        void drain() {
            while(!information.isEmpty() || !generation.isEmpty()) {
                while(!information.isEmpty())information.remove().run();
                while(!generation.isEmpty())generation.remove().run();
            }
        }
    }
    private static final String ROUTE="204000057";
    private static final Instant START=Instant.parse("2026-11-02T00:00:00Z");
    @Autowired JdbcClient jdbc;
    @Autowired JdbcRouteDisplayGenerationStore store;
    @Autowired RouteDisplaySourceReader source;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ApplicationEventPublisher events;
    @Autowired Tasks tasks;
    @Autowired RefreshRouteCatalog catalogRefresher;
    @Autowired RefreshRouteGeometry geometryCollector;
    private MutableClock clock;
    private RouteDisplayGenerationWorker worker;

    @BeforeEach void reset() {
        assertThat(tasks.information).isEmpty();assertThat(tasks.generation).isEmpty();
        org.mockito.Mockito.reset(catalogRefresher,geometryCollector);
        jdbc.sql("TRUNCATE route_display_generation_job,route_display_automatic_artifact").update();
        jdbc.sql("TRUNCATE route CASCADE").update();
        RouteDisplayDbSamples.seed(jdbc,ROUTE);
        clock=new MutableClock(START);
        worker=restartedWorker();
    }
    @AfterEach void drainSignals() { tasks.drain(); }
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

    private long newVersion(boolean rollback) {
        long old=current().referenceVersionId();
        return new TransactionTemplate(transactions).execute(tx -> {
            long route=jdbc.sql("SELECT route_id FROM route_version WHERE id=?").param(old).query(Long.class).single();
            jdbc.sql("UPDATE route_version SET valid_to=? WHERE id=?").params(OffsetDateTime.ofInstant(START,ZoneOffset.UTC),old).update();
            long next=jdbc.sql("INSERT INTO route_version(route_id,turn_sequence,content_digest,valid_from) VALUES (?,2,?,?) RETURNING id")
                .params(route,"c".repeat(64),OffsetDateTime.ofInstant(START,ZoneOffset.UTC)).query(Long.class).single();
            jdbc.sql("""
                INSERT INTO route_stop(route_version_id,stop_order,stop_id,name,direction,boarding_allowed)
                SELECT ?,stop_order,CASE WHEN stop_order=2 THEN '206000639' ELSE stop_id END,name,direction,boarding_allowed
                FROM route_stop WHERE route_version_id=?
                """).params(next,old).update();
            jdbc.sql("""
                INSERT INTO route_stop_location_history(route_version_id,stop_order,stop_id,observed_at,x,y)
                SELECT ?,stop_order,CASE WHEN stop_order=2 THEN '206000639' ELSE stop_id END,observed_at,x,y
                FROM route_stop_location_history WHERE route_version_id=?
                """).params(next,old).update();
            events.publishEvent(new RouteVersionChanged(route,next));
            assertThat(tasks.information).as("No refresh may run before commit").isEmpty();
            if(rollback)tx.setRollbackOnly();
            return next;
        });
    }

    private void collectNewGeometryFrom(long oldVersion) {
        org.mockito.Mockito.when(geometryCollector.refresh(org.mockito.ArgumentMatchers.eq(ROUTE),org.mockito.ArgumentMatchers.any()))
            .thenAnswer(call -> {
                long current=current().referenceVersionId();
                jdbc.sql("""
                    INSERT INTO route_geometry(id,route_version_id,received_at,content_digest,points,evidence)
                    SELECT ?,?,clock_timestamp(),content_digest,points,evidence FROM route_geometry WHERE route_version_id=?
                    ORDER BY received_at DESC LIMIT 1
                    """).params(UUID.randomUUID(),current,oldVersion).update();
                events.publishEvent(new RouteGeometryInputAvailable(ROUTE,current));
                return RefreshRouteGeometry.Outcome.UPDATED;
            });
    }

    @Test void a_committed_new_version_collects_and_generates_geometry_without_waiting_for_a_schedule() {
        long old=current().referenceVersionId();collectNewGeometryFrom(old);
        long next=newVersion(false);
        assertThat(tasks.information).hasSize(1);assertThat(tasks.generation).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(geometryCollector);
        tasks.drain();
        assertThat(current().referenceVersionId()).isEqualTo(next);assertThat(current().shape()).isNotNull();
        assertThat(artifactCount()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT input_json #>> '{referenceVersionId}' FROM route_display_generation_job")
            .query(String.class).list()).containsOnly(Long.toString(next));
        assertThat(jdbc.sql("SELECT status FROM route_display_generation_job").query(String.class).list()).containsOnly("COMPLETED");
        org.mockito.Mockito.verify(geometryCollector,org.mockito.Mockito.times(1)).refresh(org.mockito.ArgumentMatchers.eq(ROUTE),org.mockito.ArgumentMatchers.any());
    }

    @Test void a_rolled_back_version_does_not_enqueue_source_collection() {
        long old=current().referenceVersionId();newVersion(true);
        assertThat(current().referenceVersionId()).isEqualTo(old);assertThat(tasks.information).isEmpty();
        assertThat(tasks.generation).isEmpty();org.mockito.Mockito.verifyNoInteractions(geometryCollector,catalogRefresher);
    }

    @Test void an_observation_mismatch_can_confirm_a_new_version_and_publish_its_geometry() {
        long old=current().referenceVersionId();collectNewGeometryFrom(old);
        org.mockito.Mockito.when(catalogRefresher.refresh(org.mockito.ArgumentMatchers.eq(ROUTE),org.mockito.ArgumentMatchers.any()))
            .thenAnswer(call -> Optional.of(new RouteReference(newVersion(false))));
        events.publishEvent(new ObservationRouteMismatchDetected(ROUTE,old));
        assertThat(tasks.information).hasSize(1);tasks.drain();
        assertThat(current().referenceVersionId()).isNotEqualTo(old);assertThat(artifactCount()).isEqualTo(2);
        org.mockito.Mockito.verify(catalogRefresher,org.mockito.Mockito.times(1)).refresh(org.mockito.ArgumentMatchers.eq(ROUTE),org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(geometryCollector,org.mockito.Mockito.times(1)).refresh(org.mockito.ArgumentMatchers.eq(ROUTE),org.mockito.ArgumentMatchers.any());
    }

    @Test void an_unchanged_catalog_after_mismatch_keeps_the_version_and_geometry() {
        long old=current().referenceVersionId();
        org.mockito.Mockito.when(catalogRefresher.refresh(org.mockito.ArgumentMatchers.eq(ROUTE),org.mockito.ArgumentMatchers.any()))
            .thenReturn(Optional.of(new RouteReference(old)));
        events.publishEvent(new ObservationRouteMismatchDetected(ROUTE,old));tasks.drain();
        assertThat(current().referenceVersionId()).isEqualTo(old);assertThat(current().shape()).isNotNull();
        assertThat(artifactCount()).isZero();org.mockito.Mockito.verifyNoInteractions(geometryCollector);
    }

    @Test void mismatch_resolution_uses_the_upstream_route_id_even_when_the_public_id_differs() {
        long version=current().referenceVersionId();String sourceId="204000081";
        jdbc.sql("UPDATE route SET source_route_id=? WHERE public_route_id=?").params(sourceId,ROUTE).update();
        org.mockito.Mockito.when(catalogRefresher.refresh(org.mockito.ArgumentMatchers.eq(sourceId),org.mockito.ArgumentMatchers.any()))
            .thenReturn(Optional.of(new RouteReference(version)));
        events.publishEvent(new ObservationRouteMismatchDetected(sourceId,version));tasks.drain();
        org.mockito.Mockito.verify(catalogRefresher).refresh(org.mockito.ArgumentMatchers.eq(sourceId),org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(catalogRefresher,org.mockito.Mockito.never()).refresh(org.mockito.ArgumentMatchers.eq(ROUTE),org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verifyNoInteractions(geometryCollector);
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
        assertThat(worker.runOne()).isTrue();
        assertThat(jdbc.sql("SELECT status FROM route_display_generation_job").query(String.class).list())
            .containsOnly("COMPLETED").hasSize(2);
        assertThat(jdbc.sql("SELECT attempts FROM route_display_generation_job").query(Integer.class).list()).containsOnly(1);
        worker.discover(ROUTE);assertThat(worker.runOne()).isFalse();
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

    @Test void transient_execution_failures_exhaust_the_persisted_budget_and_discovery_does_not_revive_it() {
        worker.discover(ROUTE);
        String sourceKey=jdbc.sql("SELECT generation_key FROM route_display_generation_job WHERE algorithm_revision=?")
            .param(RouteDisplayGeneration.SOURCE_REVISION).query(String.class).single();
        jdbc.sql("UPDATE route_display_generation_job SET status='SUPERSEDED' WHERE algorithm_revision=?")
            .param(AutomaticRouteDisplay.REVISION).update();
        var unavailable=org.mockito.Mockito.spy(source);
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("temporary database failure"))
            .when(unavailable).snapshotProvenanceMatches(org.mockito.Mockito.any(RouteGeometry.class),org.mockito.Mockito.nullable(String.class));
        var failingWorker=new RouteDisplayGenerationWorker(store,unavailable,json,transactions,Optional.of(clock));
        for(int attempt=1;attempt<=5;attempt++) {
            assertThat(failingWorker.runOne()).isTrue();
            assertThat(state(sourceKey)).isEqualTo(attempt==5?"FAILED":"PENDING");
            if(attempt<5)jdbc.sql("UPDATE route_display_generation_job SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE generation_key=?")
                .param(sourceKey).update();
        }
        assertThat(failingWorker.runOne()).isFalse();assertThat(artifactCount()).isZero();
        worker=restartedWorker();worker.discover(ROUTE);
        assertThat(state(sourceKey)).isEqualTo("FAILED");
        assertThat(jdbc.sql("SELECT attempts FROM route_display_generation_job WHERE generation_key=?").param(sourceKey).query(Integer.class).single()).isEqualTo(5);
        // The independent full-generation job can still complete after source-only execution failed.
        assertThat(worker.runOne()).isTrue();assertThat(artifactCount()).isEqualTo(1);
        assertThat(worker.runOne()).isFalse();
    }

    @Test void malformed_immutable_snapshot_is_failed_once_instead_of_superseded_and_rediscovered() {
        var g=RouteDisplayGeneration.at(current(),clock.instant());
        String digest=RouteDisplayGeneration.input(g).digest(),evidence=g.shape().evidence().revision();
        String key=RouteDisplayGeneration.key(digest,evidence,RouteDisplayGeneration.SOURCE_REVISION,g.shape().sourceManifestId());
        store.enqueue(key,ROUTE,digest,evidence,RouteDisplayGeneration.SOURCE_REVISION,"{}",g.shape().sourceManifestId());
        assertThat(worker.runOne()).isTrue();assertThat(state(key)).isEqualTo("FAILED");assertThat(artifactCount()).isZero();
        worker.discover(ROUTE);
        assertThat(state(key)).isEqualTo("FAILED");
        assertThat(jdbc.sql("SELECT attempts || ':' || last_failure_code FROM route_display_generation_job WHERE generation_key=?")
            .param(key).query(String.class).single()).isEqualTo("1:INVALID_SNAPSHOT");
    }

    @Test void retry_backoff_is_explicit_and_large_attempt_numbers_cannot_overflow() {
        assertThat(java.util.stream.IntStream.rangeClosed(1,7).mapToObj(RouteDisplayGenerationWorker::retryDelay)
            .map(Duration::toSeconds).toList()).containsExactly(10L,20L,40L,80L,160L,300L,300L);
        assertThat(RouteDisplayGenerationWorker.retryDelay(Integer.MAX_VALUE)).isEqualTo(Duration.ofSeconds(300));
        assertThatThrownBy(()->RouteDisplayGenerationWorker.retryDelay(0)).isInstanceOf(IllegalArgumentException.class);
    }
    private static class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now=now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
