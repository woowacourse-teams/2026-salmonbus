package com.gustler.backend.worker.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import com.gustler.backend.quota.api.ApiCallQuota;
import com.gustler.backend.routecatalog.api.RefreshRouteGeometry;
import com.gustler.backend.routecatalog.api.RefreshRouteGeometry.Outcome;
import com.gustler.backend.routecatalog.application.RouteGeometryRefresher;
import com.gustler.backend.routecatalog.domain.geometry.RouteGeometrySource;
import com.gustler.backend.routecatalog.domain.geometry.RouteShape;
import com.gustler.backend.routecatalog.domain.geometry.SupplementaryRouteGeometrySource;
import com.gustler.backend.routecatalog.domain.geometry.SupplementaryRouteGeometrySource.Contribution;
import com.gustler.backend.routecatalog.infrastructure.jdbc.JdbcRouteGeometryStore;
import com.gustler.backend.routegeometry.api.RouteDisplayDbSamples;
import com.gustler.backend.routegeometry.api.RouteDisplayInput;
import com.gustler.backend.routegeometry.api.RouteShapeEvidence;
import com.gustler.backend.support.PostgresTestContainer;
import com.gustler.backend.worker.routegeometry.RouteDisplaySourceReader;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes=RouteGeometryRecoveryTest.Config.class,properties="route-geometry.enabled=true")
class RouteGeometryRecoveryTest {
    @Configuration(proxyBeanMethods=false)
    @EnableAutoConfiguration
    @Import({PostgresTestContainer.class,JdbcRouteGeometryStore.class,RouteGeometryRefresher.class,
        JdbcRouteGeometryRecoveryStore.class,RecoveringRouteGeometryRefresh.class,RouteDisplaySourceReader.class})
    static class Config {
        @Bean ObjectMapper json(){return new ObjectMapper();}
        @Bean RouteGeometrySource primary(){return mock(RouteGeometrySource.class);}
        @Bean ApiCallQuota quota(){return mock(ApiCallQuota.class);}
        @Bean SupplementaryRouteGeometrySource web(){return mock(SupplementaryRouteGeometrySource.class);}
        @Bean SupplementaryRouteGeometrySource bms(){return mock(SupplementaryRouteGeometrySource.class);}
        @Bean(name="routeGeometryExecutor") Executor executor(){return Runnable::run;}
    }
    @Autowired JdbcClient jdbc;
    @Autowired JdbcRouteGeometryRecoveryStore jobs;
    @Autowired RecoveringRouteGeometryRefresh refresh;
    @Autowired RouteGeometryRefresher delegate;
    @Autowired RouteDisplaySourceReader reader;
    @Autowired PlatformTransactionManager transactions;
    @Autowired RouteGeometrySource primary;
    @Autowired ApiCallQuota quota;
    @Autowired @Qualifier("web") SupplementaryRouteGeometrySource web;
    @Autowired @Qualifier("bms") SupplementaryRouteGeometrySource bms;
    static final String ROUTE="204000057";
    long version;
    static final RouteShape SHAPE=new RouteShape(List.of(new RouteShape.Point(127,37),new RouteShape.Point(127.01,37.01),new RouteShape.Point(127,37)));

    @BeforeEach void setup() {
        jdbc.sql("TRUNCATE route CASCADE").update();version=RouteDisplayDbSamples.seed(jdbc,ROUTE);
        jdbc.sql("DELETE FROM route_geometry").update();
        reset(primary,quota,web,bms);
        when(primary.read(anyString())).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return Optional.of(new RouteGeometrySource.Read(SHAPE,OffsetDateTime.now()));
        });
        when(quota.reserveRouteCatalog(any(),anyInt())).thenReturn(true);
        when(web.id()).thenReturn("GBIS_WEB");when(bms.id()).thenReturn("GG_BMS_NODES");
        when(web.read(anyString())).thenReturn(availableWeb(false));
        when(bms.read(anyString())).thenReturn(Contribution.unavailable("GG_BMS_NODES"));
    }
    Contribution availableWeb(boolean mismatch) {
        var roles=new ArrayList<RouteShapeEvidence.TypedPoint>();
        for(int i=0;i<SHAPE.points().size();i++) {
            var p=SHAPE.points().get(i);
            roles.add(new RouteShapeEvidence.TypedPoint(new RouteDisplayInput.Point(p.x()+(mismatch?.1:0),p.y()),3,i<2?"UP":"DOWN",false));
        }
        return new Contribution(Optional.empty(),new RouteShapeEvidence.Source("GBIS_WEB","AVAILABLE","a".repeat(64)),roles,List.of());
    }
    void due(){jdbc.sql("UPDATE route_geometry_recovery SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE status='PENDING'").update();}
    int attempts(){return jdbc.sql("SELECT attempts FROM route_geometry_recovery WHERE route_version_id=?").param(version).query(Integer.class).single();}
    void unavailable(){when(primary.read(anyString())).thenReturn(Optional.empty());when(web.read(anyString())).thenReturn(Contribution.unavailable("GBIS_WEB"));}
    void exhaust(){unavailable();assertThat(refresh.ensure(ROUTE,OffsetDateTime.now())).isEqualTo(Outcome.RECOVERY_SCHEDULED);for(int i=0;i<4;i++){due();refresh.recover();}}

    @Test void publishedApiShapeStillRetriesMissingWebEvidence() {
        when(web.read(anyString())).thenReturn(Contribution.unavailable("GBIS_WEB"));
        assertThat(refresh.ensure(ROUTE,OffsetDateTime.now())).isEqualTo(Outcome.RECOVERY_SCHEDULED);
        assertThat(jdbc.sql("SELECT count(*) FROM route_geometry").query(Integer.class).single()).isEqualTo(1);
        assertThat(jobs.status(version)).contains("PENDING");
        assertThat(refresh.ensure(ROUTE,OffsetDateTime.now())).isEqualTo(Outcome.RECOVERY_SCHEDULED);
        verify(primary,times(1)).read(ROUTE);
        when(web.read(anyString())).thenReturn(availableWeb(false));due();refresh.recover();
        assertThat(jobs.status(version)).contains("SUCCEEDED");assertThat(attempts()).isEqualTo(2);
        verify(primary,times(2)).read(ROUTE);
    }
    @Test void allSourceFailuresEndAfterFiveAttemptsAndDuplicateSignalsDoNotResetTheBudget() {
        exhaust();assertThat(jobs.status(version)).contains("EXHAUSTED");assertThat(attempts()).isEqualTo(5);
        for(int i=0;i<10;i++)assertThat(refresh.ensure(ROUTE,OffsetDateTime.now())).isEqualTo(Outcome.RECOVERY_EXHAUSTED);
        refresh.recover();verify(primary,times(5)).read(ROUTE);
    }
    @Test void aRestartRetainsTheBackoffAndAttemptBudget() {
        unavailable();refresh.ensure(ROUTE,OffsetDateTime.now());
        var restarted=new JdbcRouteGeometryRecoveryStore(jdbc);
        assertThat(restarted.claim(version)).isEmpty();assertThat(attempts()).isEqualTo(1);
        due();assertThat(restarted.claim(version).orElseThrow().attempts()).isEqualTo(2);
    }
    @Test void scheduledCollectionCanStartANewCycleAfterExhaustion() {
        exhaust();when(primary.read(anyString())).thenReturn(Optional.of(new RouteGeometrySource.Read(SHAPE,OffsetDateTime.now())));
        when(web.read(anyString())).thenReturn(availableWeb(false));
        assertThat(refresh.refresh(ROUTE,OffsetDateTime.now())).isEqualTo(Outcome.UPDATED);
        assertThat(jobs.status(version)).contains("SUCCEEDED");assertThat(attempts()).isEqualTo(1);
    }
    @Test void expiredLeaseCannotCompleteOverTheReplacementWorker() {
        jobs.request(ROUTE,false);var old=jobs.claim(version).orElseThrow();
        jdbc.sql("UPDATE route_geometry_recovery SET lease_until=clock_timestamp()-interval '1 second'").update();
        var replacement=jobs.claim(version).orElseThrow();
        assertThat(replacement.attempts()).isEqualTo(2);assertThat(replacement.token()).isNotEqualTo(old.token());
        assertThat(jobs.finish(old,false,false,"UPDATED")).isFalse();
        assertThat(jobs.finish(replacement,false,false,"UPDATED")).isTrue();
    }
    @Test void finalExpiredLeaseBecomesExhaustedWithoutASixthAttempt() {
        jobs.request(ROUTE,false);
        for(int i=0;i<4;i++){var job=jobs.claim(version).orElseThrow();jobs.finish(job,true,false,"WEB_UNAVAILABLE");due();}
        var last=jobs.claim(version).orElseThrow();assertThat(last.attempts()).isEqualTo(5);
        jdbc.sql("UPDATE route_geometry_recovery SET lease_until=clock_timestamp()-interval '1 second'").update();
        assertThat(jobs.claim(version)).isEmpty();assertThat(jobs.status(version)).contains("EXHAUSTED");
        assertThat(jobs.finish(last,false,false,"UPDATED")).isFalse();
    }
    @Test void versionChangeDuringHttpCannotPublishToTheOldVersion() {
        var once=new AtomicBoolean();
        when(primary.read(anyString())).thenAnswer(call->{
            if(once.compareAndSet(false,true)) {
                jdbc.sql("UPDATE route_version SET valid_to=clock_timestamp() WHERE id=?").param(version).update();
                jdbc.sql("INSERT INTO route_version(route_id,turn_sequence,content_digest,valid_from) SELECT route_id,turn_sequence,?,clock_timestamp() FROM route_version WHERE id=?")
                    .params("c".repeat(64),version).update();
            }
            return Optional.of(new RouteGeometrySource.Read(SHAPE,OffsetDateTime.now()));
        });
        assertThat(refresh.ensure(ROUTE,OffsetDateTime.now())).isEqualTo(Outcome.REFERENCE_CHANGED);
        assertThat(jobs.status(version)).contains("SUPERSEDED");
        assertThat(jdbc.sql("SELECT count(*) FROM route_geometry").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM route_geometry_recovery WHERE status='PENDING'").query(Integer.class).single()).isEqualTo(1);
    }
    @Test void concurrentWorkersClaimTheSameAttemptOnlyOnce() throws Exception {
        jobs.request(ROUTE,false);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var results=pool.invokeAll(List.of(()->jobs.claim(version),()->jobs.claim(version)));
            int claimed=0;for(var result:results)if(((Optional<?>)result.get()).isPresent())claimed++;
            assertThat(claimed).isEqualTo(1);assertThat(attempts()).isEqualTo(1);
        }
    }
    @Test void rejectedExecutorKeepsDurableWorkForTheNextDispatch() {
        jobs.request(ROUTE,false);var tasks=new ArrayDeque<Runnable>();var reject=new AtomicBoolean(true);
        var runner=new RecoveringRouteGeometryRefresh(delegate,jobs,reader,transactions,task->{
            if(reject.getAndSet(false))throw new RejectedExecutionException();tasks.add(task);
        },Optional.empty());runner.applicationReady();
        runner.recover();assertThat(jobs.status(version)).contains("PENDING");
        runner.recover();assertThat(tasks).hasSize(1);tasks.remove().run();
        assertThat(jobs.status(version)).contains("SUCCEEDED");
    }
    @Test void availableButUnmatchedWebRolesContinueRecoveryEvenAfterAnUnchangedPublication() {
        when(web.read(anyString())).thenReturn(availableWeb(true));
        assertThat(refresh.ensure(ROUTE,OffsetDateTime.now())).isEqualTo(Outcome.RECOVERY_SCHEDULED);
        due();refresh.recover();assertThat(attempts()).isEqualTo(2);assertThat(jobs.status(version)).contains("PENDING");
        assertThat(jdbc.sql("SELECT last_outcome FROM route_geometry_recovery").query(String.class).single()).isEqualTo("ROLE_MISMATCH");
    }
    @Test void optionalBmsFailureDoesNotBlockUsableWebRolesAndPublicIdsStaySeparate() {
        jdbc.sql("UPDATE route SET source_route_id='200000001'").update();
        assertThat(refresh.ensure(ROUTE,OffsetDateTime.now())).isEqualTo(Outcome.UPDATED);
        assertThat(jobs.status(version)).contains("SUCCEEDED");verify(primary).read("200000001");
        assertThat(refresh.ensure(ROUTE,OffsetDateTime.now())).isEqualTo(Outcome.UNCHANGED);verify(primary,times(1)).read(anyString());
    }
    @Test void repeatedScheduledSignalsDoNotResetRunningOrPendingAttempts() {
        jobs.request(ROUTE,false);var job=jobs.claim(version).orElseThrow();
        assertThat(refresh.refresh(ROUTE,OffsetDateTime.now())).isEqualTo(Outcome.RECOVERY_SCHEDULED);assertThat(attempts()).isEqualTo(1);
        jobs.finish(job,true,false,"WEB_UNAVAILABLE");
        assertThat(refresh.refresh(ROUTE,OffsetDateTime.now())).isEqualTo(Outcome.RECOVERY_SCHEDULED);assertThat(attempts()).isEqualTo(1);
        verifyNoInteractions(primary);
    }
    @Test void technicalFailureIsRecordedWithoutLosingTheRecoveryRequest() {
        when(quota.reserveRouteCatalog(any(),anyInt())).thenThrow(new IllegalStateException("test failure"));
        assertThat(refresh.ensure(ROUTE,OffsetDateTime.now())).isEqualTo(Outcome.RECOVERY_SCHEDULED);
        assertThat(jobs.status(version)).contains("PENDING");
        assertThat(jdbc.sql("SELECT last_outcome FROM route_geometry_recovery").query(String.class).single()).isEqualTo("EXECUTION_ERROR");
    }
    @Test void losingTheLeaseDuringHttpCannotPublishGeometryOrFinishTheReplacementAttempt() {
        when(primary.read(anyString())).thenAnswer(call->{
            jdbc.sql("UPDATE route_geometry_recovery SET lease_until=clock_timestamp()-interval '1 second' WHERE route_version_id=?").param(version).update();
            assertThat(jobs.claim(version)).isPresent();
            return Optional.of(new RouteGeometrySource.Read(SHAPE,OffsetDateTime.now()));
        });
        assertThat(refresh.ensure(ROUTE,OffsetDateTime.now())).isEqualTo(Outcome.RECOVERY_SCHEDULED);
        assertThat(jdbc.sql("SELECT count(*) FROM route_geometry").query(Integer.class).single()).isZero();
        assertThat(jobs.status(version)).contains("RUNNING");assertThat(attempts()).isEqualTo(2);
    }

}
