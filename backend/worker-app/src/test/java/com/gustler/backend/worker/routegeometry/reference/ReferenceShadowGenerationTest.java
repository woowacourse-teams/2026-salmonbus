package com.gustler.backend.worker.routegeometry.reference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.gustler.backend.routecatalog.api.ReadRouteModelFacts;
import com.gustler.backend.routegeometry.api.RouteModelFacts;
import com.gustler.backend.routegeometry.engine.api.*;
import com.gustler.backend.worker.routegeometry.*;
import com.gustler.backend.support.PostgresTestContainer;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** The real durable queue/store and actual candidate search, with a controlled current-source reader. */
@SpringBootTest(classes=ReferenceShadowGenerationTest.Config.class)
class ReferenceShadowGenerationTest {
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration
    @Import({PostgresTestContainer.class,JdbcRouteDisplayGenerationStore.class})
    static class Config {@Bean ObjectMapper json(){return new ObjectMapper();}}
    @Autowired JdbcClient jdbc;
    @Autowired JdbcRouteDisplayGenerationStore store;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    private RouteDisplaySourceReader sources;
    private AtomicReference<RouteGeometry> current;
    private AtomicReference<RouteModelFacts> roles;
    private ReferenceShadowGeneration shadow;
    private RouteDisplayGenerationWorker worker;
    @BeforeEach void reset(){
        jdbc.sql("TRUNCATE route_display_generation_job,route_display_automatic_artifact").update();
        var graph=SourceRoleNativeGeneratorTest.load();current=new AtomicReference<>(SourceRoleNativeGeneratorTest.geometry(graph));
        roles=new AtomicReference<>(SourceRoleNativeGeneratorTest.facts(current.get(),graph));
        sources=mock(RouteDisplaySourceReader.class);
        when(sources.readLocked(anyString())).thenAnswer(call->Optional.of(current.get()));
        when(sources.snapshotProvenanceMatches(any(),isNull())).thenReturn(true);
        var facts=mock(ReadRouteModelFacts.class);when(facts.read(anySet(),any(),anyLong())).thenAnswer(call->roles.get());
        Clock clock=Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"),ZoneOffset.UTC);
        shadow=new ReferenceShadowGeneration(store,sources,facts,json,transactions,clock,graph);
        worker=new RouteDisplayGenerationWorker(store,sources,json,transactions,Optional.of(clock),Optional.of(shadow));
    }
    private String route(){return current.get().routeId();}
    private JdbcRouteDisplayGenerationStore.Job claimedShadow(){
        worker.discover(route());assertThat(worker.runOne()).isTrue();assertThat(worker.runOne()).isTrue();
        var job=store.claim(Duration.ofMinutes(5)).orElseThrow();assertThat(job.algorithmRevision()).isEqualTo(ReferenceShadowGeneration.ALGORITHM);return job;
    }
    @Test void actual_worker_generates_baseline_first_then_keeps_candidate_in_immutable_separate_namespace(){
        worker.discover(route());worker.discover(route());
        assertThat(jdbc.sql("SELECT count(*) FROM route_display_generation_job").query(Integer.class).single()).isEqualTo(3);
        for(String algorithm:List.of(RouteDisplayGeneration.SOURCE_REVISION,AutomaticRouteDisplay.REVISION,ReferenceShadowGeneration.ALGORITHM)){
            var next=store.claim(Duration.ofMinutes(5)).orElseThrow();assertThat(next.algorithmRevision()).isEqualTo(algorithm);
            // Return ownership to the queue so the actual worker executes it.
            store.fail(next,Duration.ofMillis(1));
            jdbc.sql("UPDATE route_display_generation_job SET next_attempt_at=clock_timestamp() WHERE generation_key=?").param(next.generationKey()).update();
            assertThat(worker.runOne()).isTrue();
        }
        var documents=jdbc.sql("SELECT algorithm_revision,document FROM route_display_automatic_artifact").query((r,n)->Map.entry(r.getString(1),r.getString(2))).list();
        assertThat(documents).hasSize(3);
        var candidate=json.readValue(documents.stream().filter(e->e.getKey().equals(ReferenceShadowGeneration.ALGORITHM)).findFirst().orElseThrow().getValue(),ReferenceShadowGeneration.Artifact.class);
        assertThat(candidate.status()).isEqualTo("CANDIDATE_ONLY");assertThat(candidate.result().candidate()).isPresent();
        assertThat(candidate.published()).isFalse();assertThat(candidate.modelAdopted()).isFalse();assertThat(candidate.accuracyGrade()).isEqualTo("UNEVALUATED");
        for(var entry:documents)if(!entry.getKey().equals(ReferenceShadowGeneration.ALGORITHM))assertThat(json.readValue(entry.getValue(),RouteDisplayGeometry.class).accuracyProof()).isNotNull();
        assertThatThrownBy(()->jdbc.sql("UPDATE route_display_automatic_artifact SET document='{}' WHERE algorithm_revision=?").param(ReferenceShadowGeneration.ALGORITHM).update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void current_role_change_supersedes_claim_and_new_discovery_has_a_new_key(){
        var old=claimedShadow();var existing=roles.get();var visits=new ArrayList<>(existing.visits());var v=visits.get(62);
        visits.set(62,new RouteModelFacts.VisitFacts(v.stationId(),v.sequence(),v.direction(),new RouteModelFacts.Fact(RouteModelFacts.FieldState.VALUE,"N")));
        roles.set(new RouteModelFacts(existing.inputDigest(),existing.consumedRoles(),existing.routeValues(),visits,existing.availability(),existing.reason(),existing.basisRefs()));
        assertThat(shadow.execute(old)).isFalse();
        assertThat(jdbc.sql("SELECT status FROM route_display_generation_job WHERE generation_key=?").param(old.generationKey()).query(String.class).single()).isEqualTo("SUPERSEDED");
        worker.discover(route());assertThat(worker.runOne()).isTrue();
        assertThat(jdbc.sql("SELECT generation_key FROM route_display_automatic_artifact WHERE algorithm_revision=?").param(ReferenceShadowGeneration.ALGORITHM).query(String.class).single()).isNotEqualTo(old.generationKey());
    }
    @Test void missing_primary_roles_are_archived_as_source_fallback_not_a_verified_candidate(){
        roles.set(RouteModelFacts.unresolved(RouteDisplayGeneration.input(current.get()),Set.of(RouteModelFacts.Role.CENTER_YN),RouteModelFacts.Reason.SOURCE_UNAVAILABLE));
        worker.discover(route());for(int i=0;i<3;i++)assertThat(worker.runOne()).isTrue();
        var document=jdbc.sql("SELECT document FROM route_display_automatic_artifact WHERE algorithm_revision=?").param(ReferenceShadowGeneration.ALGORITHM).query(String.class).single();
        var artifact=json.readValue(document,ReferenceShadowGeneration.Artifact.class);
        assertThat(artifact.status()).isEqualTo("SOURCE_FALLBACK");assertThat(artifact.result().candidate()).isEmpty();assertThat(artifact.published()).isFalse();
    }
    @Test void baseline_publication_rejects_shadow_before_interpreting_its_snapshot_or_document(){
        var job=claimedShadow();assertThatThrownBy(()->worker.publish(job,"not display json")).hasMessage("Shadow artifacts cannot enter display publication");
        assertThat(shadow.execute(job)).isTrue();assertThat(shadow.execute(job)).isFalse();
    }
    @Test void worker_without_opt_in_retires_pending_shadow_without_publishing_it(){
        var job=claimedShadow();store.fail(job,Duration.ofMillis(1));jdbc.sql("UPDATE route_display_generation_job SET next_attempt_at=clock_timestamp()").update();
        var disabled=new RouteDisplayGenerationWorker(store,sources,json,transactions,Optional.empty());assertThat(disabled.runOne()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM route_display_automatic_artifact WHERE algorithm_revision=?").param(ReferenceShadowGeneration.ALGORITHM).query(Integer.class).single()).isZero();
    }
    @Test void runtime_projection_dependency_identity_binds_versions_definitions_and_rejects_missing_metadata() throws Exception {
        Map<String,byte[]> resources=new HashMap<>();
        for(String path:List.of("/META-INF/maven/org.locationtech.proj4j/proj4j/pom.properties",
                "/META-INF/maven/org.locationtech.proj4j/proj4j-epsg/pom.properties","/proj4/nad/epsg")) {
            try(var input=getClass().getResourceAsStream(path)) {assertThat(input).isNotNull();resources.put(path,input.readAllBytes());}
        }
        String original=ReferenceShadowGeneration.dependencyDigest(resources::get);
        assertThat(ReferenceShadowGeneration.dependencyDigest(resources::get)).isEqualTo(original);
        var changed=new HashMap<>(resources);String metadata="/META-INF/maven/org.locationtech.proj4j/proj4j/pom.properties";
        changed.put(metadata,new String(resources.get(metadata),java.nio.charset.StandardCharsets.ISO_8859_1).replace("version=1.4.3","version=1.4.4").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        assertThat(ReferenceShadowGeneration.dependencyDigest(changed::get)).isNotEqualTo(original);
        changed=new HashMap<>(resources);changed.put("/proj4/nad/epsg","changed-definition".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(ReferenceShadowGeneration.dependencyDigest(changed::get)).isNotEqualTo(original);
        changed.remove(metadata);var missing=changed;
        assertThatThrownBy(()->ReferenceShadowGeneration.dependencyDigest(missing::get)).isInstanceOf(IllegalStateException.class).hasMessageContaining("unavailable");
    }

    @Test void actual_worker_archives_global_viterbi_next_to_the_unchanged_precision_candidate() {
        var graph=GlobalReferenceMatcherAdapterTest.completeGraph();
        var facts=mock(ReadRouteModelFacts.class);when(facts.read(anySet(),any(),anyLong())).thenAnswer(call->roles.get());
        Clock clock=Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"),ZoneOffset.UTC);
        var combined=new ReferenceShadowGeneration(store,sources,facts,json,transactions,clock,graph);
        var actual=new RouteDisplayGenerationWorker(store,sources,json,transactions,Optional.of(clock),Optional.of(combined));
        actual.discover(route());for(int i=0;i<3;i++)assertThat(actual.runOne()).isTrue();
        String document=jdbc.sql("SELECT document FROM route_display_automatic_artifact WHERE algorithm_revision=?").param(ReferenceShadowGeneration.ALGORITHM).query(String.class).single();
        var artifact=json.readValue(document,ReferenceShadowGeneration.Artifact.class);
        assertThat(artifact.result().candidate().orElseThrow().nativeCoordinatesXYZ()).hasSize(157);
        assertThat(artifact.globalResult().globalResult().selections()).hasSizeGreaterThan(2);
        assertThat(artifact.modelAdopted()).isFalse();assertThat(artifact.published()).isFalse();assertThat(artifact.globalResult().published()).isFalse();
        assertThat(artifact.globalResult().bmsPriorStatus()).isEqualTo("UNKNOWN_NO_PINNED_BMS_SOURCE");
    }

}
