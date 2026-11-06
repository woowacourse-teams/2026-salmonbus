package com.gustler.backend.worker.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.gustler.backend.observations.api.ObservationRouteMismatchDetected;
import com.gustler.backend.routecatalog.api.RefreshRouteCatalog;
import com.gustler.backend.routecatalog.api.RefreshRouteGeometry;
import com.gustler.backend.routecatalog.api.RefreshRouteGeometry.Outcome;
import com.gustler.backend.routecatalog.api.RouteReference;
import com.gustler.backend.routecatalog.api.RouteVersionChanged;
import com.gustler.backend.worker.configuration.CollectionProperties;
import com.gustler.backend.worker.configuration.RouteGeometryProperties;
import com.gustler.backend.worker.scheduling.JdbcRouteRefreshStateRepository.State;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RouteChangeRefreshCoordinatorTest {
    private static final String ROUTE = "204000057";
    private final RefreshRouteCatalog catalog = mock(RefreshRouteCatalog.class);
    private final RefreshRouteGeometry geometry = mock(RefreshRouteGeometry.class);
    private final JdbcRouteRefreshStateRepository states = mock(JdbcRouteRefreshStateRepository.class);
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    private final MutableClock clock = new MutableClock();
    private final AtomicReference<State> state = new AtomicReference<>(new State(1,ROUTE,ROUTE,7,false));
    private RouteChangeRefreshCoordinator coordinator;

    @BeforeEach void setup() {
        when(states.findByRouteId(1)).thenAnswer(call -> Optional.of(state.get()));
        when(states.findBySourceId(ROUTE)).thenAnswer(call -> Optional.of(state.get()));
        when(catalog.refresh(anyString(), any())).thenAnswer(call -> Optional.of(new RouteReference(state.get().versionId())));
        when(geometry.ensure(anyString(), any())).thenReturn(Outcome.UPDATED);
        coordinator=create(tasks::add, new RouteGeometryProperties(true,List.of(ROUTE)));
    }

    private RouteChangeRefreshCoordinator create(Executor executor, RouteGeometryProperties properties) {
        var result=new RouteChangeRefreshCoordinator(catalog,geometry,states,executor,
                new CollectionProperties(true,List.of(ROUTE)),properties,Optional.of(clock),Duration.ofMinutes(1));
        result.applicationReady();return result;
    }
    private void drain() { while(!tasks.isEmpty())tasks.remove().run(); }
    private void mismatch(long version) { coordinator.observationMismatch(new ObservationRouteMismatchDetected(ROUTE,version)); }

    @Test void early_version_signals_wait_until_the_startup_reference_sweep_is_queued() {
        coordinator=new RouteChangeRefreshCoordinator(catalog,geometry,states,tasks::add,
                new CollectionProperties(true,List.of(ROUTE)),new RouteGeometryProperties(true,List.of(ROUTE)),Optional.of(clock),Duration.ofMinutes(1));
        coordinator.versionChanged(new RouteVersionChanged(1,7));coordinator.retryPending();
        assertThat(tasks).isEmpty();verifyNoInteractions(catalog,geometry,states);
        coordinator.applicationReady();assertThat(tasks).hasSize(1);drain();verify(geometry).ensure(eq(ROUTE),any());
    }

    @Test void committed_version_signal_queues_HTTP_off_the_calling_thread_and_merges_duplicates() {
        for(int i=0;i<50;i++)coordinator.versionChanged(new RouteVersionChanged(1,7));
        assertThat(tasks).hasSize(1);verifyNoInteractions(geometry,catalog,states);
        drain();verify(geometry,times(1)).ensure(eq(ROUTE),any());verifyNoInteractions(catalog);
    }

    @Test void a_daily_sweep_that_already_collected_this_version_does_not_fetch_it_twice() {
        state.set(new State(1,ROUTE,ROUTE,7,true));coordinator.versionChanged(new RouteVersionChanged(1,7));drain();
        verifyNoInteractions(catalog,geometry);
    }

    @Test void a_late_version_signal_resolves_the_latest_current_version() {
        state.set(new State(1,ROUTE,ROUTE,8,false));coordinator.versionChanged(new RouteVersionChanged(1,7));drain();
        verify(geometry,times(1)).ensure(eq(ROUTE),any());assertThat(state.get().versionId()).isEqualTo(8);
    }

    @Test void a_new_version_arriving_during_HTTP_is_not_lost() {
        var attempts=new ArrayList<Long>();
        when(geometry.ensure(anyString(),any())).thenAnswer(call -> {
            attempts.add(state.get().versionId());
            if(attempts.size()==1){state.set(new State(1,ROUTE,ROUTE,8,false));coordinator.versionChanged(new RouteVersionChanged(1,8));return Outcome.REFERENCE_CHANGED;}
            state.set(new State(1,ROUTE,ROUTE,8,true));return Outcome.UPDATED;
        });
        coordinator.versionChanged(new RouteVersionChanged(1,7));drain();
        assertThat(attempts).containsExactly(7L,8L);
    }

    @Test void failed_acquisition_retries_only_the_signalled_version_with_a_bounded_budget() {
        when(geometry.ensure(anyString(),any())).thenReturn(Outcome.SOURCE_UNAVAILABLE);
        coordinator.versionChanged(new RouteVersionChanged(1,7));drain();
        coordinator.retryPending();assertThat(tasks).isEmpty();
        for(int i=0;i<4;i++){clock.advance(Duration.ofMinutes(10));coordinator.retryPending();drain();}
        clock.advance(Duration.ofHours(1));coordinator.retryPending();assertThat(tasks).isEmpty();
        verify(geometry,times(5)).ensure(eq(ROUTE),any());verifyNoInteractions(catalog);
    }

    @Test void reference_races_without_a_visible_new_version_back_off_instead_of_spinning() {
        when(geometry.ensure(anyString(),any())).thenReturn(Outcome.REFERENCE_CHANGED);
        coordinator.versionChanged(new RouteVersionChanged(1,7));drain();
        verify(geometry,times(1)).ensure(eq(ROUTE),any());coordinator.retryPending();assertThat(tasks).isEmpty();
    }

    @Test void duplicate_signals_during_the_last_attempt_do_not_reset_the_version_budget() {
        var attemptedVersions=new ArrayList<Long>();
        when(geometry.ensure(anyString(),any())).thenAnswer(call->{
            attemptedVersions.add(state.get().versionId());
            if(attemptedVersions.size()==5)coordinator.versionChanged(new RouteVersionChanged(1,7));
            return Outcome.SOURCE_UNAVAILABLE;
        });
        coordinator.versionChanged(new RouteVersionChanged(1,7));drain();
        for(int i=0;i<4;i++){clock.advance(Duration.ofMinutes(10));coordinator.retryPending();drain();}
        mismatch(7);drain();
        assertThat(attemptedVersions).containsExactly(7L,7L,7L,7L,7L);
        state.set(new State(1,ROUTE,ROUTE,8,false));
        coordinator.versionChanged(new RouteVersionChanged(1,8));drain();
        assertThat(attemptedVersions).containsExactly(7L,7L,7L,7L,7L,8L);
    }

    @Test void rejected_submission_retains_the_request_for_the_dispatch_retry() {
        var submissions=new AtomicInteger();coordinator=create(task->{if(submissions.getAndIncrement()==0)throw new RejectedExecutionException();tasks.add(task);},new RouteGeometryProperties(true,List.of(ROUTE)));
        coordinator.versionChanged(new RouteVersionChanged(1,7));assertThat(tasks).isEmpty();
        coordinator.retryPending();drain();verify(geometry).ensure(eq(ROUTE),any());
    }

    @Test void mismatch_refreshes_the_catalog_once_and_cools_down_repeated_signals() {
        state.set(new State(1,ROUTE,ROUTE,7,true));for(int i=0;i<50;i++)mismatch(7);drain();
        for(int i=0;i<50;i++)mismatch(7);drain();coordinator.retryPending();
        verify(catalog,times(1)).refresh(eq(ROUTE),any());verifyNoInteractions(geometry);
        clock.advance(Duration.ofMinutes(1));coordinator.retryPending();drain();
        verify(catalog,times(2)).refresh(eq(ROUTE),any());
    }

    @Test void a_confirmed_catalog_change_collects_the_new_version_without_a_second_event_fetch() {
        state.set(new State(1,ROUTE,ROUTE,7,true));
        doAnswer(call->{state.set(new State(1,ROUTE,ROUTE,8,true));return Outcome.UPDATED;})
            .when(geometry).ensure(eq(ROUTE),any());
        when(catalog.refresh(eq(ROUTE),any())).thenAnswer(call -> {
            state.set(new State(1,ROUTE,ROUTE,8,false));coordinator.versionChanged(new RouteVersionChanged(1,8));return Optional.of(new RouteReference(8));
        });
        mismatch(7);drain();
        verify(catalog,times(1)).refresh(eq(ROUTE),any());verify(geometry,times(1)).ensure(eq(ROUTE),any());
        assertThat(state.get()).isEqualTo(new State(1,ROUTE,ROUTE,8,true));
    }

    @Test void old_observations_after_a_version_change_do_not_recheck_the_catalog() {
        state.set(new State(1,ROUTE,ROUTE,8,true));mismatch(7);drain();verifyNoInteractions(catalog,geometry);
    }

    @Test void mismatch_checks_the_upstream_id_and_collects_geometry_by_the_public_id() {
        String publicId="204000081";
        state.set(new State(1,publicId,ROUTE,7,true));
        coordinator=create(tasks::add,new RouteGeometryProperties(true,List.of(publicId)));
        when(catalog.refresh(eq(ROUTE),any())).thenAnswer(call->{
            state.set(new State(1,publicId,ROUTE,8,false));coordinator.versionChanged(new RouteVersionChanged(1,8));
            return Optional.of(new RouteReference(8));
        });
        mismatch(7);drain();
        verify(catalog).refresh(eq(ROUTE),any());verify(catalog,never()).refresh(eq(publicId),any());
        verify(geometry).ensure(eq(publicId),any());
    }

    @Test void temporary_catalog_failure_keeps_a_request_without_immediate_retry_storm() {
        when(catalog.refresh(eq(ROUTE),any())).thenReturn(Optional.empty(),Optional.of(new RouteReference(7)));
        state.set(new State(1,ROUTE,ROUTE,7,true));mismatch(7);drain();coordinator.retryPending();assertThat(tasks).isEmpty();
        clock.advance(Duration.ofMinutes(1));coordinator.retryPending();drain();verify(catalog,times(2)).refresh(eq(ROUTE),any());
    }

    @Test void observation_recovery_also_works_when_map_collection_is_disabled() {
        coordinator=create(tasks::add,new RouteGeometryProperties(false,List.of()));
        mismatch(7);drain();verify(catalog).refresh(eq(ROUTE),any());verifyNoInteractions(geometry);
    }

    @Test void explicit_map_scope_does_not_collect_an_unconfigured_route_version() {
        coordinator=create(tasks::add,new RouteGeometryProperties(true,List.of("204000081")));
        coordinator.versionChanged(new RouteVersionChanged(1,7));drain();verifyNoInteractions(catalog,geometry);
    }

    @Test void an_inherited_collection_scope_matches_source_ids_and_fetches_by_public_id() {
        String publicId="204000081";
        state.set(new State(1,publicId,ROUTE,7,false));
        coordinator=create(tasks::add,new RouteGeometryProperties(true,List.of()));
        coordinator.versionChanged(new RouteVersionChanged(1,7));drain();
        verify(geometry).ensure(eq(publicId),any());verifyNoInteractions(catalog);
    }

    private static final class MutableClock extends Clock {
        private Instant now=Instant.parse("2026-11-04T00:00:00Z");
        void advance(Duration duration){now=now.plus(duration);}
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
    }
}
