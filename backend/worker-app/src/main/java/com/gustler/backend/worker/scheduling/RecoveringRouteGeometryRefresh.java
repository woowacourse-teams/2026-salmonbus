package com.gustler.backend.worker.scheduling;

import com.gustler.backend.routecatalog.api.RefreshRouteGeometry;
import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeneration;
import com.gustler.backend.worker.routegeometry.RouteDisplaySourceReader;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Primary
@ConditionalOnProperty(name="route-geometry.enabled",havingValue="true")
public class RecoveringRouteGeometryRefresh implements RefreshRouteGeometry {
    private static final Logger LOG=LoggerFactory.getLogger(RecoveringRouteGeometryRefresh.class);
    private final RefreshRouteGeometry delegate;
    private final JdbcRouteGeometryRecoveryStore jobs;
    private final RouteDisplaySourceReader source;
    private final TransactionTemplate transaction;
    private final Executor executor;
    private final Clock clock;
    private final AtomicBoolean queued=new AtomicBoolean();
    private volatile boolean ready;

    public RecoveringRouteGeometryRefresh(@Qualifier("routeGeometryRefresher") RefreshRouteGeometry delegate,
            JdbcRouteGeometryRecoveryStore jobs, RouteDisplaySourceReader source,
            PlatformTransactionManager transactions,@Qualifier("routeGeometryExecutor") Executor executor,Optional<Clock> clock) {
        this.delegate=delegate;this.jobs=jobs;this.source=source;this.transaction=new TransactionTemplate(transactions);
        this.executor=executor;this.clock=clock.orElseGet(Clock::systemUTC);
    }

    @Override
    public Outcome refresh(String routeId,OffsetDateTime requestedAt) { return start(routeId,requestedAt,true); }

    @Override
    public Outcome ensure(String routeId,OffsetDateTime requestedAt) { return start(routeId,requestedAt,false); }

    private Outcome start(String routeId,OffsetDateTime requestedAt,boolean scheduled) {
        var version=jobs.request(routeId,scheduled);
        if(version.isEmpty())return Outcome.NO_REFERENCE;
        var job=jobs.claim(version.get());
        if(job.isPresent())return run(job.get(),requestedAt);
        return switch(jobs.status(version.get()).orElse("PENDING")) {
            case "SUCCEEDED" -> Outcome.UNCHANGED;
            case "EXHAUSTED" -> Outcome.RECOVERY_EXHAUSTED;
            default -> Outcome.RECOVERY_SCHEDULED;
        };
    }

    private Outcome run(JdbcRouteGeometryRecoveryStore.Job job,OffsetDateTime requestedAt) {
        if(!jobs.current(job)) {
            jobs.finish(job,false,true,"REFERENCE_CHANGED");jobs.request(job.routeId(),false);
            return Outcome.REFERENCE_CHANGED;
        }
        try {
            var acquired=delegate.refreshAttempt(job.routeId(),requestedAt,new Attempt(job.versionId(),job.token()));
            var outcome=acquired.outcome();
            boolean obsolete=outcome==Outcome.REFERENCE_CHANGED || outcome==Outcome.NO_REFERENCE || !jobs.current(job);
            boolean stored=outcome==Outcome.UPDATED || outcome==Outcome.UNCHANGED;
            boolean mismatch=stored && Boolean.TRUE.equals(transaction.execute(tx -> source.readLocked(job.routeId())
                    .filter(g->g.referenceVersionId()==job.versionId())
                    .map(RouteDisplayGeneration::roleEvidenceNeedsRefresh).orElse(false)));
            boolean retry=!obsolete && (!stored || acquired.supplementaryRecoveryNeeded() || mismatch);
            String reason=mismatch?"ROLE_MISMATCH":acquired.supplementaryRecoveryNeeded()?"WEB_UNAVAILABLE":outcome.name();
            jobs.finish(job,retry,obsolete,reason);
            if(obsolete)jobs.request(job.routeId(),false);
            LOG.info("event=route_geometry_recovery routeId={} routeVersionId={} attempt={} outcome={} retry={}",
                    job.routeId(),job.versionId(),job.attempts(),reason,retry);
            return retry?(job.attempts()>=5?Outcome.RECOVERY_EXHAUSTED:Outcome.RECOVERY_SCHEDULED):outcome;
        } catch(RuntimeException failure) {
            jobs.finish(job,true,false,"EXECUTION_ERROR");
            LOG.warn("event=route_geometry_recovery_failed routeId={} routeVersionId={} attempt={} failureType={}",
                    job.routeId(),job.versionId(),job.attempts(),failure.getClass().getSimpleName());
            return job.attempts()>=5?Outcome.RECOVERY_EXHAUSTED:Outcome.RECOVERY_SCHEDULED;
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(2)
    public void applicationReady() { ready=true; }

    @Scheduled(fixedDelayString="${route-geometry.change-refresh.retry-delay-ms:10000}",scheduler="collectionTaskScheduler")
    public void recover() {
        if(!ready || !queued.compareAndSet(false,true))return;
        try {
            executor.execute(()->{
                try {
                    for(int i=0;i<16;i++) {
                        var job=jobs.claim(null);if(job.isEmpty())break;
                        run(job.get(),OffsetDateTime.now(clock));
                    }
                } finally {queued.set(false);}
            });
        } catch(RejectedExecutionException rejected) {
            queued.set(false);
            LOG.warn("event=route_geometry_recovery_deferred reason=QUEUE_FULL");
        }
    }
}
