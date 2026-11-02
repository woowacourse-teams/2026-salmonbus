package com.gustler.backend.worker.routegeometry;

import com.gustler.backend.routegeometry.engine.api.AutomaticRouteDisplay;
import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeneration;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry;
import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeometry;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Discovery survives process loss through durable jobs; generation runs outside database transactions. */
@Component
@ConditionalOnProperty(name="route-geometry.generation.enabled",havingValue="true")
public class RouteDisplayGenerationWorker {
    private static final Logger LOG=LoggerFactory.getLogger(RouteDisplayGenerationWorker.class);
    private final JdbcRouteDisplayGenerationStore store;
    private final RouteDisplaySourceReader source;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Optional<com.gustler.backend.worker.routegeometry.reference.ReferenceShadowGeneration> shadow;
    public RouteDisplayGenerationWorker(JdbcRouteDisplayGenerationStore store,RouteDisplaySourceReader source,
            ObjectMapper json,PlatformTransactionManager transactions,Optional<Clock> clock) {
        this(store,source,json,transactions,clock,Optional.empty());
    }
    @org.springframework.beans.factory.annotation.Autowired
    public RouteDisplayGenerationWorker(JdbcRouteDisplayGenerationStore store,RouteDisplaySourceReader source,
            ObjectMapper json,PlatformTransactionManager transactions,Optional<Clock> clock,
            Optional<com.gustler.backend.worker.routegeometry.reference.ReferenceShadowGeneration> shadow) {
        this.store=store;this.source=source;this.json=json;
        this.transaction=new TransactionTemplate(transactions);this.clock=clock.orElseGet(Clock::systemUTC);this.shadow=shadow;
    }
    @Scheduled(fixedDelayString="${route-geometry.generation.poll-delay-ms:10000}", scheduler="routeDisplayGenerationScheduler")
    public void poll() {
        for (String route : source.routes()) {
            try { discover(route); }
            catch (RuntimeException failure) { LOG.warn("event=route_display_discovery_failed routeId={}",route); }
        }
        for (int i=0;i<16;i++) if (!runOne()) break;
    }
    public void discover(String route) {
        transaction.executeWithoutResult(tx -> source.readLocked(route).ifPresent(raw -> {
            if(raw.shape()==null || raw.busStops().isEmpty()) return;
            var g=RouteDisplayGeneration.at(raw,clock.instant());
            String digest=RouteDisplayGeneration.input(g).digest(), evidence=g.shape().evidence().revision();
            String snapshot=json.writeValueAsString(g);
            // Validate the current source first; it can be served while candidate generation is pending.
            for(String algorithm : new String[]{RouteDisplayGeneration.SOURCE_REVISION,AutomaticRouteDisplay.REVISION})
                store.enqueue(RouteDisplayGeneration.key(digest,evidence,algorithm,g.shape().sourceManifestId()),route,digest,evidence,algorithm,snapshot,g.shape().sourceManifestId());
        }));
        // A separate transaction prevents an optional shadow input failure from rolling back baseline discovery.
        shadow.ifPresent(candidate->{
            try {candidate.discover(route);}
            catch(RuntimeException failure) {LOG.warn("event=route_native_shadow_discovery_failed routeId={}",route);}
        });
    }
    public boolean runOne() {
        var claimed=store.claim(Duration.ofMinutes(5));
        if(claimed.isEmpty()) return false;
        var job=claimed.get();
        long started=System.nanoTime();
        LOG.info("event=route_display_generation_started routeId={} generationKey={} attempt={} ageAtClaimMs={} algorithm={}",
                job.routeId(),job.generationKey(),job.attempts(),job.ageAtClaimMillis(),job.algorithmRevision());
        try {
            if(job.algorithmRevision().equals(com.gustler.backend.worker.routegeometry.reference.ReferenceShadowGeneration.ALGORITHM)) {
                if(shadow.isPresent())shadow.get().execute(job);else store.supersede(job);
                return true;
            }
            if (!job.algorithmRevision().equals(AutomaticRouteDisplay.REVISION)
                    && !job.algorithmRevision().equals(RouteDisplayGeneration.SOURCE_REVISION)) {
                store.supersede(job);
                LOG.info("event=route_display_generation_superseded routeId={} generationKey={} attempt={} reason=ALGORITHM_RETIRED",job.routeId(),job.generationKey(),job.attempts());
                return true;
            }
            var snapshot=json.readValue(job.inputJson(),RouteGeometry.class);
            if (!source.snapshotProvenanceMatches(snapshot,job.sourceManifestId()) || !matches(job,snapshot)) {
                store.supersede(job);
                LOG.info("event=route_display_generation_superseded routeId={} generationKey={} attempt={} reason=INVALID_SNAPSHOT",job.routeId(),job.generationKey(),job.attempts());
                return true;
            }
            var generated=job.algorithmRevision().equals(RouteDisplayGeneration.SOURCE_REVISION)
                ? AutomaticRouteDisplay.sourceOnly(snapshot,job.inputDigest())
                : AutomaticRouteDisplay.build(snapshot,job.inputDigest());
            generated=baselineProof(job,snapshot,generated);
            String document=json.writeValueAsString(generated);
            if(!generated.equals(json.readValue(document,RouteDisplayGeometry.class)))
                throw new IllegalStateException("Serialized artifact changed");
            LOG.info("event=route_display_generation_completed routeId={} generationKey={} attempt={} generationMs={} quality={}",
                    job.routeId(),job.generationKey(),job.attempts(),elapsedMillis(started),generated.quality().status());
            publish(job,document);
        } catch(RuntimeException failure) {
            long delay=Math.min(300,1L << Math.min(job.attempts(),8));
            store.fail(job,Duration.ofSeconds(delay));
            LOG.warn("event=route_display_generation_failed routeId={} generationKey={} attempt={} elapsedMs={} retryDelaySeconds={} failureType={}",
                    job.routeId(),job.generationKey(),job.attempts(),elapsedMillis(started),delay,failure.getClass().getSimpleName());
        }
        return true;
    }
    public boolean publish(JdbcRouteDisplayGenerationStore.Job job,String document) {
        if(job.algorithmRevision().equals(com.gustler.backend.worker.routegeometry.reference.ReferenceShadowGeneration.ALGORITHM))
            throw new IllegalArgumentException("Shadow artifacts cannot enter display publication");
        long started=System.nanoTime();
        var snapshot=json.readValue(job.inputJson(),RouteGeometry.class);
        if(!source.snapshotProvenanceMatches(snapshot,job.sourceManifestId()) || !matches(job,snapshot)) {
            store.supersede(job);
            LOG.info("event=route_display_publication routeId={} generationKey={} attempt={} outcome=INVALID_SNAPSHOT publicationMs={}",
                job.routeId(),job.generationKey(),job.attempts(),elapsedMillis(started));
            return false;
        }
        var supplied=json.readValue(document,RouteDisplayGeometry.class);
        String sealedDocument=json.writeValueAsString(baselineProof(job,snapshot,supplied));
        String outcome=transaction.execute(tx -> {
            var current=source.readLocked(job.routeId());
            if(current.isEmpty() || current.get().shape()==null ||
                    !matches(job,RouteDisplayGeneration.at(current.get(),clock.instant()))) {
                store.supersede(job); return "SOURCE_STALE";
            }
            return store.complete(job,sealedDocument,RouteDisplayGeneration.sha256(sealedDocument)) ? "PUBLISHED" : "LEASE_LOST";
        });
        LOG.info("event=route_display_publication routeId={} generationKey={} attempt={} outcome={} publicationMs={}",
                job.routeId(),job.generationKey(),job.attempts(),outcome,elapsedMillis(started));
        return "PUBLISHED".equals(outcome);
    }
    /** The baseline has no adopted native model inputs; caller-supplied grades cannot promote it. */
    private RouteDisplayGeometry baselineProof(JdbcRouteDisplayGenerationStore.Job job,RouteGeometry input,RouteDisplayGeometry output) {
        var evaluated=input.shape().evidenceEvaluatedAt()==null ? job.createdAt() : input.shape().evidenceEvaluatedAt();
        var context=new com.gustler.backend.routegeometry.engine.api.accuracy.AccuracyEvidence.OperatingContext("BUS",evaluated);
        var report=com.gustler.backend.routegeometry.engine.api.accuracy.RouteAccuracyVerifier.unassessed(input,output,context);
        if(output.accuracyProof()!=null && !output.accuracyProof().equals(report.snapshot()))
            throw new IllegalArgumentException("Baseline cannot publish caller-supplied accuracy proof");
        return output.withAccuracyProof(report.snapshot());
    }

    private static long elapsedMillis(long started) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
    }
    private boolean matches(JdbcRouteDisplayGenerationStore.Job job,RouteGeometry g) {
        return job.routeId().equals(g.routeId()) && job.inputDigest().equals(RouteDisplayGeneration.input(g).digest())
            && job.evidenceRevision().equals(g.shape().evidence().revision())
            && job.generationKey().equals(RouteDisplayGeneration.key(job.inputDigest(),job.evidenceRevision(),job.algorithmRevision(),g.shape().sourceManifestId()));
    }
}
