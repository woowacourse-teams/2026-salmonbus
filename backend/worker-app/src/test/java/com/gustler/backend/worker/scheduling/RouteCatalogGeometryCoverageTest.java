package com.gustler.backend.worker.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gustler.backend.routecatalog.api.RefreshRouteCatalog;
import com.gustler.backend.routecatalog.api.RefreshRouteGeometry;
import com.gustler.backend.routecatalog.api.RouteReference;
import com.gustler.backend.worker.configuration.CollectionProperties;
import com.gustler.backend.worker.configuration.RouteGeometryProperties;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RouteCatalogGeometryCoverageTest {
    private final RefreshRouteCatalog catalog = mock(RefreshRouteCatalog.class);
    private final RefreshRouteGeometry geometry = mock(RefreshRouteGeometry.class);

    @Test
    void 전체_41개와_추가노선을_매번_발견하고_중복없이_한개의_큐작업으로_수집한다() {
        var initialRoutes = routes(41);
        var expandedRoutes = routes(42);
        var tasks = new ArrayBlockingQueue<Runnable>(1);
        when(catalog.currentGbisRouteIds()).thenReturn(initialRoutes).thenReturn(expandedRoutes);
        when(catalog.refresh(anyString(), any())).thenReturn(Optional.of(new RouteReference(7)));
        var scheduler = scheduler(true, List.of(initialRoutes.getFirst()), true,
            List.of(initialRoutes.get(1)), tasks::add);

        scheduler.refreshAllRoutes();
        assertThat(tasks).hasSize(1);
        verify(catalog, never()).currentGbisRouteIds();
        verifyNoInteractions(geometry);
        tasks.remove().run();
        for (String routeId : initialRoutes) {
            verify(catalog).refresh(eq(routeId), any());
            verify(geometry).refresh(eq(routeId), any());
        }

        scheduler.refreshAllRoutes();
        assertThat(tasks).hasSize(1);
        tasks.remove().run();
        verify(catalog, times(2)).currentGbisRouteIds();
        for (String routeId : initialRoutes) {
            verify(catalog, times(2)).refresh(eq(routeId), any());
            verify(geometry, times(2)).refresh(eq(routeId), any());
        }
        verify(catalog).refresh(eq(expandedRoutes.getLast()), any());
        verify(geometry).refresh(eq(expandedRoutes.getLast()), any());
    }

    @Test
    void 일부_노선의_카탈로그나_형상_실패가_다른_노선의_갱신을_막지_않는다() {
        var routes = routes(41);
        when(catalog.currentGbisRouteIds()).thenReturn(routes);
        when(catalog.refresh(anyString(), any())).thenReturn(Optional.of(new RouteReference(7)));
        when(catalog.refresh(eq(routes.get(1)), any())).thenReturn(Optional.empty());
        when(catalog.refresh(eq(routes.get(2)), any())).thenThrow(new IllegalStateException("upstream unavailable"));
        when(geometry.refresh(eq(routes.get(3)), any())).thenThrow(new IllegalStateException("shape unavailable"));

        scheduler(false, List.of(), true, List.of(), Runnable::run).refreshAllRoutes();

        for (String routeId : routes) verify(catalog).refresh(eq(routeId), any());
        verify(geometry, never()).refresh(eq(routes.get(1)), any());
        verify(geometry, never()).refresh(eq(routes.get(2)), any());
        for (String routeId : routes) {
            if (!routeId.equals(routes.get(1)) && !routeId.equals(routes.get(2))) {
                verify(geometry).refresh(eq(routeId), any());
            }
        }
    }

    @Test
    void 관측노선의_카탈로그_갱신에_실패하면_형상을_잘못된_판본에_발행하지_않는다() {
        var routes = routes(2);
        when(catalog.currentGbisRouteIds()).thenReturn(routes);
        when(catalog.refresh(eq(routes.getFirst()), any())).thenReturn(Optional.empty());
        when(catalog.refresh(eq(routes.getLast()), any())).thenReturn(Optional.of(new RouteReference(7)));

        scheduler(true, List.of(routes.getFirst()), true, List.of(), Runnable::run).refreshAllRoutes();

        verify(catalog).refresh(eq(routes.getFirst()), any());
        verify(geometry, never()).refresh(eq(routes.getFirst()), any());
        verify(geometry).refresh(eq(routes.getLast()), any());
    }

    @Test
    void 전체조회가_실패해도_명시노선과_관측설정노선은_수집한다() {
        var routes = routes(2);
        when(catalog.currentGbisRouteIds()).thenThrow(new IllegalStateException("catalog unavailable"));
        when(catalog.refresh(anyString(), any())).thenReturn(Optional.of(new RouteReference(7)));

        scheduler(false, List.of(routes.getFirst()), true, List.of(routes.getLast()), Runnable::run).refreshAllRoutes();

        for (String routeId : routes) {
            verify(catalog).refresh(eq(routeId), any());
            verify(geometry).refresh(eq(routeId), any());
        }
    }

    @Test
    void 이전_전체작업이_대기중이면_중복큐를_쌓지_않고_완료후에는_다시_조회한다() {
        var tasks = new ArrayBlockingQueue<Runnable>(1);
        when(catalog.currentGbisRouteIds()).thenReturn(routes(41));
        when(catalog.refresh(anyString(), any())).thenReturn(Optional.of(new RouteReference(7)));
        var scheduler = scheduler(false, List.of(), true, List.of(), tasks::add);

        scheduler.refreshAllRoutes();
        scheduler.refreshAllRoutes();
        assertThat(tasks).hasSize(1);
        tasks.remove().run();
        scheduler.refreshAllRoutes();
        assertThat(tasks).hasSize(1);
        tasks.remove().run();

        verify(catalog, times(2)).currentGbisRouteIds();
    }

    @Test
    void 큐가_거부한_전체작업은_다음_주기에_다시_시도한다() {
        var submissions = new AtomicInteger();
        var tasks = new ArrayBlockingQueue<Runnable>(1);
        Executor executor = task -> {
            if (submissions.getAndIncrement() == 0) throw new RejectedExecutionException();
            tasks.add(task);
        };
        when(catalog.currentGbisRouteIds()).thenReturn(routes(1));
        when(catalog.refresh(anyString(), any())).thenReturn(Optional.of(new RouteReference(7)));
        var scheduler = scheduler(false, List.of(), true, List.of(), executor);

        scheduler.refreshAllRoutes();
        assertThat(tasks).isEmpty();
        scheduler.refreshAllRoutes();
        assertThat(tasks).hasSize(1);
        tasks.remove().run();
        verify(geometry).refresh(eq(routes(1).getFirst()), any());
    }

    @Test
    void 기본설정은_전체목록을_조회하지_않고_명시한_노선만_형상을_수집한다() {
        var routes = routes(2);
        when(catalog.refresh(anyString(), any())).thenReturn(Optional.of(new RouteReference(7)));
        var scheduler = scheduler(true, List.of(routes.getFirst()), false, List.of(routes.getLast()), Runnable::run);

        scheduler.refreshAllRoutes();

        verify(catalog, never()).currentGbisRouteIds();
        verify(geometry, never()).refresh(eq(routes.getFirst()), any());
        verify(geometry).refresh(eq(routes.getLast()), any());
    }

    @Test
    void 형상을_끄면_전체목록옵션을_켜도_형상작업을_만들지_않는다() {
        var tasks = new ArrayBlockingQueue<Runnable>(1);
        var scheduler = new RouteCatalogRefreshScheduler(new CollectionProperties(false, routes(1)),
            catalog, Clock.systemUTC(), geometry, tasks::add,
            new RouteGeometryProperties(false, List.of(), true));

        scheduler.refreshAllRoutes();

        assertThat(tasks).isEmpty();
        verifyNoInteractions(catalog, geometry);
    }

    private RouteCatalogRefreshScheduler scheduler(boolean collectionEnabled, List<String> collectionRoutes,
        boolean includeCatalog, List<String> geometryRoutes, Executor executor) {
        return new RouteCatalogRefreshScheduler(new CollectionProperties(collectionEnabled, collectionRoutes),
            catalog, Clock.systemUTC(), geometry, executor,
            new RouteGeometryProperties(true, geometryRoutes, includeCatalog));
    }

    private List<String> routes(int count) {
        return IntStream.range(0, count).mapToObj(index -> Integer.toString(204000000 + index)).toList();
    }
}
