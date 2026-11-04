package com.gustler.backend.worker.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.gustler.backend.routecatalog.api.RefreshRouteCatalog;
import com.gustler.backend.routecatalog.api.RefreshRouteGeometry;
import com.gustler.backend.routecatalog.api.RefreshRouteGeometryEvidence;
import com.gustler.backend.routecatalog.api.RouteReference;
import com.gustler.backend.worker.configuration.CollectionProperties;
import com.gustler.backend.worker.configuration.RouteGeometryProperties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class RouteCatalogRefreshSchedulerTest {
    @Test
    void 형상_대상을_생략하면_설정된_수집_노선의_형상을_갱신한다() {
        // given
        RefreshRouteCatalog catalog = mock(RefreshRouteCatalog.class);
        RefreshRouteGeometry geometry = mock(RefreshRouteGeometry.class);
        when(catalog.refresh(anyString(), any())).thenReturn(Optional.of(new RouteReference(7)));
        List<String> routes = List.of("204000057", "204000081", "234000002");
        RouteCatalogRefreshScheduler scheduler =
            new RouteCatalogRefreshScheduler(new CollectionProperties(false, routes),
                catalog, Clock.systemUTC(), geometry, Runnable::run, new RouteGeometryProperties(true, List.of()));

        scheduler.refreshAllRoutes();

        // when
        for (String route : routes) {

            // then
            verify(catalog).refresh(eq(route), any());
            verify(geometry).refresh(eq(route), any());
        }
        verifyNoMoreInteractions(catalog, geometry);
    }

    @Test
    void 지도전용노선은_별도실행기에서_갱신하고_공통노선은_한번만_조회한다() {
        // when
        RefreshRouteCatalog catalog = mock(RefreshRouteCatalog.class);
        RefreshRouteGeometry geometry = mock(RefreshRouteGeometry.class);
        when(catalog.refresh(anyString(), any())).thenReturn(Optional.of(new RouteReference(7)));
        ArrayDeque<Runnable> tasks = new ArrayDeque<Runnable>();
        RouteCatalogRefreshScheduler scheduler =
            new RouteCatalogRefreshScheduler(new CollectionProperties(true, List.of("204000057")),
                catalog, Clock.systemUTC(), geometry, tasks::add,
                new RouteGeometryProperties(true, List.of("204000057", "204000155")));
        scheduler.refreshAllRoutes();

        // then
        verifyNoInteractions(catalog, geometry);
        assertThat(tasks).hasSize(1);
        while (!tasks.isEmpty()) {
            tasks.remove().run();
        }
        verify(catalog, times(1)).refresh(eq("204000057"), any());
        verify(catalog).refresh(eq("204000155"), any());
        verify(geometry).refresh(eq("204000057"), any());
        verify(geometry).refresh(eq("204000155"), any());
    }

    @Test
    void 차량관측을_끄고_형상만_켜면_HTTP는_모두_별도실행기에_맡긴다() {
        // when
        RefreshRouteCatalog catalog = mock(RefreshRouteCatalog.class);
        RefreshRouteGeometry geometry = mock(RefreshRouteGeometry.class);
        ArrayDeque<Runnable> tasks = new ArrayDeque<Runnable>();
        RouteCatalogRefreshScheduler scheduler =
            new RouteCatalogRefreshScheduler(new CollectionProperties(false, List.of("204000155")),
                catalog, Clock.systemUTC(), geometry, tasks::add,
                new RouteGeometryProperties(true, List.of("204000057")));
        scheduler.refreshAllRoutes();

        // then
        verifyNoInteractions(catalog, geometry);
        assertThat(tasks).hasSize(1);
    }

    @Test
    void 실패_이유와_알려진_이름만_남기고_예외_본문은_노출하지_않는다() {
        // when
        RefreshRouteCatalog catalog = mock(RefreshRouteCatalog.class);
        when(catalog.knownRouteName("id-1")).thenReturn("3330");
        when(catalog.knownRouteName("id-2")).thenReturn("");
        when(catalog.refresh(eq("id-1"), any())).thenReturn(Optional.empty());
        when(catalog.refresh(eq("id-2"), any()))
                .thenThrow(new IllegalStateException("serviceKey=SECRET\nHTTP body"));
        when(catalog.refresh(eq("id-3"), any())).thenReturn(Optional.of(new RouteReference(3)));
        RouteCatalogRefreshScheduler scheduler =
                new RouteCatalogRefreshScheduler(
                        new CollectionProperties(true, List.of("id-1", "id-2", "id-3")),
                        catalog,
                        Clock.systemUTC(),
                        mock(RefreshRouteGeometry.class),
                        Runnable::run,
                        new RouteGeometryProperties(false, List.of()));
        Logger logger = (Logger) LoggerFactory.getLogger(RouteCatalogRefreshScheduler.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try {
            scheduler.refreshAllRoutes();

            // then
            assertThat(logs.list).hasSize(2);
            assertThat(logs.list.getFirst().getFormattedMessage()).contains(
                "routeId=\"id-1\"", "routeName=\"3330\"", "reason=UPSTREAM_OR_QUOTA");
            assertThat(logs.list.getLast().getFormattedMessage()).contains(
                "routeId=\"id-2\"", "routeName=\"\"", "reason=REFRESH_EXCEPTION",
            "exceptionType=\"IllegalStateException\"")
                .doesNotContain("SECRET", "HTTP body");
            assertThat(logs.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
            verify(catalog).refresh(eq("id-3"), any());
            verify(catalog, never()).knownRouteName("id-3");
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void 대기중_작업을_합치고_실패하면_다음_회차에_다시_조회한다() {
        var catalog = mock(RefreshRouteCatalog.class);
        var geometry = mock(RefreshRouteGeometry.class);
        when(catalog.refresh(anyString(), any()))
                .thenReturn(Optional.empty(), Optional.of(new RouteReference(7)));
        var tasks = new ArrayDeque<Runnable>();
        var scheduler =
                new RouteCatalogRefreshScheduler(
                        new CollectionProperties(false, List.of()),
                        catalog,
                        Clock.systemUTC(),
                        geometry,
                        tasks::add,
                        new RouteGeometryProperties(true, List.of("204000057")));
        scheduler.refreshAllRoutes();
        scheduler.refreshAllRoutes();
        assertThat(tasks).hasSize(1);
        tasks.remove().run();
        verifyNoInteractions(geometry);
        scheduler.refreshAllRoutes();
        tasks.remove().run();
        verify(geometry).refresh(eq("204000057"), any());
    }

    @Test
    void 큐가_거부해도_다음회차의_재시도를_막지_않는다() {
        var catalog = mock(RefreshRouteCatalog.class);
        var geometry = mock(RefreshRouteGeometry.class);
        when(catalog.refresh(anyString(), any())).thenReturn(Optional.of(new RouteReference(7)));
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.Executor executor =
                task -> {
                    if (attempts.getAndIncrement() == 0)
                        throw new java.util.concurrent.RejectedExecutionException();
                    task.run();
                };
        var scheduler =
                new RouteCatalogRefreshScheduler(
                        new CollectionProperties(false, List.of()),
                        catalog,
                        Clock.systemUTC(),
                        geometry,
                        executor,
                        new RouteGeometryProperties(true, List.of("204000057")));
        scheduler.refreshAllRoutes();
        scheduler.refreshAllRoutes();
        verify(geometry).refresh(eq("204000057"), any());
    }

    @Test
    void 기동제출이_거절돼도_새Ready없이_보관된요청만_재제출한다() {
        var catalog = mock(RefreshRouteCatalog.class);
        var geometry = mock(RefreshRouteGeometry.class);
        var evidence = mock(RefreshRouteGeometryEvidence.class);
        when(catalog.refresh(anyString(), any())).thenReturn(Optional.of(new RouteReference(7)));
        var submissions = new AtomicInteger();
        var tasks = new ArrayDeque<Runnable>();
        var scheduler = new RouteCatalogRefreshScheduler(new CollectionProperties(false, List.of()), catalog,
            Clock.systemUTC(), geometry, task -> {
                if (submissions.getAndIncrement() == 0) throw new java.util.concurrent.RejectedExecutionException();
                tasks.add(task);
            }, new RouteGeometryProperties(true, List.of("204000057")), Optional.of(evidence));

        scheduler.retryQueuedRefresh();
        assertThat(submissions.get()).isZero();
        scheduler.onApplicationReady();
        assertThat(tasks).isEmpty();
        verifyNoInteractions(evidence, catalog, geometry);
        scheduler.retryQueuedRefresh();
        scheduler.retryQueuedRefresh();
        assertThat(tasks).hasSize(1);
        assertThat(submissions.get()).isEqualTo(2);
        tasks.remove().run();
        verify(evidence, times(1)).refresh();
        verify(catalog, times(1)).refresh(eq("204000057"), any());
        verify(geometry, times(1)).refresh(eq("204000057"), any());
        scheduler.retryQueuedRefresh();
        assertThat(tasks).isEmpty();
        assertThat(submissions.get()).isEqualTo(2);
    }

    @Test
    void 기동과_정기호출을_합치고_공통BMS를_먼저한번_갱신한다() {
        var catalog = mock(RefreshRouteCatalog.class);
        var geometry = mock(RefreshRouteGeometry.class);
        var evidence = mock(RefreshRouteGeometryEvidence.class);
        when(catalog.refresh(anyString(), any())).thenReturn(Optional.of(new RouteReference(7)));
        var tasks = new ArrayDeque<Runnable>();
        var scheduler = new RouteCatalogRefreshScheduler(new CollectionProperties(true, List.of("204000057")),
            catalog, Clock.systemUTC(), geometry, tasks::add,
            new RouteGeometryProperties(true, List.of("204000057", "204000155")), Optional.of(evidence));

        scheduler.onApplicationReady();
        scheduler.refreshAllRoutes();
        assertThat(tasks).hasSize(1);
        verifyNoInteractions(evidence, catalog, geometry);
        tasks.remove().run();

        var ordered = inOrder(evidence, catalog, geometry);
        ordered.verify(evidence).refresh();
        ordered.verify(catalog).refresh(eq("204000057"), any());
        ordered.verify(geometry).refresh(eq("204000057"), any());
        ordered.verify(catalog).refresh(eq("204000155"), any());
        ordered.verify(geometry).refresh(eq("204000155"), any());
        verify(evidence, times(1)).refresh();

        scheduler.onApplicationReady();
        assertThat(tasks).isEmpty();
        verify(evidence, times(1)).refresh();
        scheduler.refreshAllRoutes();
        assertThat(tasks).hasSize(1);
        tasks.remove().run();
        verify(evidence, times(2)).refresh();
        verify(catalog, times(2)).refresh(eq("204000057"), any());
        verify(catalog, times(2)).refresh(eq("204000155"), any());
        verify(geometry, times(2)).refresh(eq("204000057"), any());
        verify(geometry, times(2)).refresh(eq("204000155"), any());
    }

    @Test
    void BMS응답을_기다리는중에도_트리거스레드를_막지않고_실행중_중복을_합친다() throws Exception {
        var catalog = mock(RefreshRouteCatalog.class);
        var geometry = mock(RefreshRouteGeometry.class);
        var evidence = mock(RefreshRouteGeometryEvidence.class);
        when(catalog.refresh(anyString(), any())).thenReturn(Optional.of(new RouteReference(7)));
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            started.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test release timed out");
            return null;
        }).when(evidence).refresh();
        var submissions = new AtomicInteger();
        try (var sourceExecutor = Executors.newSingleThreadExecutor(); var triggerExecutor = Executors.newSingleThreadExecutor()) {
            var scheduler = new RouteCatalogRefreshScheduler(new CollectionProperties(false, List.of()), catalog,
                Clock.systemUTC(), geometry, task -> {
                    submissions.incrementAndGet();
                    sourceExecutor.execute(task);
                }, new RouteGeometryProperties(true, List.of("204000057")), Optional.of(evidence));
            try {
                triggerExecutor.submit(scheduler::onApplicationReady).get(2, TimeUnit.SECONDS);
                assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
                scheduler.refreshAllRoutes();
                assertThat(submissions.get()).isEqualTo(1);
                verifyNoInteractions(catalog, geometry);
                release.countDown();
                sourceExecutor.submit(() -> {}).get(2, TimeUnit.SECONDS);
                scheduler.refreshAllRoutes();
                sourceExecutor.submit(() -> {}).get(2, TimeUnit.SECONDS);
                assertThat(submissions.get()).isEqualTo(2);
                verify(evidence, times(2)).refresh();
                verify(geometry, times(2)).refresh(eq("204000057"), any());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void BMS와_실패노선의_이름조회가_실패해도_다음노선을_수집하고_본문은_숨긴다() {
        var catalog = mock(RefreshRouteCatalog.class);
        var geometry = mock(RefreshRouteGeometry.class);
        var evidence = mock(RefreshRouteGeometryEvidence.class);
        doThrow(new IllegalStateException("serviceKey=SECRET evidence body")).when(evidence).refresh();
        when(catalog.refresh(eq("204000057"), any())).thenThrow(new IllegalStateException("serviceKey=SECRET catalog body"));
        when(catalog.knownRouteName("204000057")).thenThrow(new IllegalStateException("serviceKey=SECRET name body"));
        when(catalog.refresh(eq("204000155"), any())).thenReturn(Optional.of(new RouteReference(7)));
        var scheduler = new RouteCatalogRefreshScheduler(new CollectionProperties(false, List.of()), catalog,
            Clock.systemUTC(), geometry, Runnable::run, new RouteGeometryProperties(true, List.of("204000057", "204000155")),
            Optional.of(evidence));
        Logger logger = (Logger) LoggerFactory.getLogger(RouteCatalogRefreshScheduler.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try {
            scheduler.refreshAllRoutes();
            verify(evidence).refresh();
            verify(geometry, never()).refresh(eq("204000057"), any());
            verify(geometry).refresh(eq("204000155"), any());
            assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("event=route_geometry_evidence_refresh_failed", "IllegalStateException"));
            assertThat(logs.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain("SECRET", "evidence body", "catalog body", "name body");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void 형상을_끄고_관측카탈로그만_수집하면_BMS는_호출하지않는다() {
        var catalog = mock(RefreshRouteCatalog.class);
        var geometry = mock(RefreshRouteGeometry.class);
        var evidence = mock(RefreshRouteGeometryEvidence.class);
        when(catalog.refresh(anyString(), any())).thenReturn(Optional.of(new RouteReference(7)));
        var scheduler = new RouteCatalogRefreshScheduler(new CollectionProperties(true, List.of("204000057")),
            catalog, Clock.systemUTC(), geometry, Runnable::run,
            new RouteGeometryProperties(false, List.of(), true), Optional.of(evidence));
        scheduler.onApplicationReady();
        verify(catalog).refresh(eq("204000057"), any());
        verifyNoInteractions(evidence, geometry);
    }

    @Test
    void 기동과_서울04시_수집요청과_거절요청만_재제출하는_진입점을_등록한다() throws Exception {
        var event = RouteCatalogRefreshScheduler.class.getMethod("onApplicationReady").getAnnotation(EventListener.class);
        assertThat(event.value()).containsExactly(ApplicationReadyEvent.class);
        assertThat(RouteCatalogRefreshScheduler.class.getMethod("onApplicationReady").getAnnotation(Order.class).value()).isZero();
        var schedule = RouteCatalogRefreshScheduler.class.getMethod("refreshAllRoutes").getAnnotation(Scheduled.class);
        assertThat(schedule.cron()).isEqualTo("0 0 4 * * *");
        assertThat(schedule.zone()).isEqualTo("Asia/Seoul");
        assertThat(schedule.scheduler()).isEqualTo("collectionTaskScheduler");
        assertThat(schedule.fixedDelayString()).isEmpty();
        var dispatch = RouteCatalogRefreshScheduler.class.getMethod("retryQueuedRefresh").getAnnotation(Scheduled.class);
        assertThat(dispatch.fixedDelayString()).isEqualTo("PT10S");
        assertThat(dispatch.cron()).isEmpty();
        assertThat(dispatch.scheduler()).isEqualTo("collectionTaskScheduler");
        var before = ZonedDateTime.of(2026, 11, 4, 3, 59, 59, 0, ZoneId.of(schedule.zone()));
        var atFour = before.plusSeconds(1);
        assertThat(CronExpression.parse(schedule.cron()).next(before)).isEqualTo(atFour);
        assertThat(CronExpression.parse(schedule.cron()).next(atFour)).isEqualTo(atFour.plusDays(1));
    }

    @Test
    void 전체수집에서도_공개ID와_원본ID를_구분하고_관측노선과_중복조회하지않는다() {
        var catalog = mock(RefreshRouteCatalog.class);
        var geometry = mock(RefreshRouteGeometry.class);
        var states = mock(JdbcRouteRefreshStateRepository.class);
        var state = new JdbcRouteRefreshStateRepository.State(1, "201000001", "202000001", 7, false);
        when(states.findByPublicId("201000001")).thenReturn(Optional.of(state));
        when(states.findBySourceId("202000001")).thenReturn(Optional.of(state));
        when(catalog.refresh(eq("202000001"), any())).thenReturn(Optional.of(new RouteReference(7)));
        when(catalog.currentGbisRouteIds()).thenReturn(List.of("201000001"));
        var explicit = new RouteCatalogRefreshScheduler(new CollectionProperties(true, List.of("202000001")),
            catalog, Clock.systemUTC(), geometry, Runnable::run,
            new RouteGeometryProperties(true, List.of("201000001"), true), Optional.empty(), Optional.of(states));
        explicit.refreshAllRoutes();
        verify(catalog, times(1)).refresh(eq("202000001"), any());
        verify(catalog, never()).refresh(eq("201000001"), any());
        verify(geometry, times(1)).refresh(eq("201000001"), any());
        verify(geometry, never()).refresh(eq("202000001"), any());

        var inherited = new RouteCatalogRefreshScheduler(new CollectionProperties(true, List.of("202000001")),
            catalog, Clock.systemUTC(), geometry, Runnable::run,
            new RouteGeometryProperties(true, List.of()), Optional.empty(), Optional.of(states));
        inherited.refreshAllRoutes();
        verify(catalog, times(2)).refresh(eq("202000001"), any());
        verify(geometry, times(2)).refresh(eq("201000001"), any());
    }

}
