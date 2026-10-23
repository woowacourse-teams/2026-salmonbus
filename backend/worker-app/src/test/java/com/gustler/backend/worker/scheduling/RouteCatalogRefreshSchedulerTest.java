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
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gustler.backend.routecatalog.api.RefreshRouteCatalog;
import com.gustler.backend.routecatalog.api.RefreshRouteGeometry;
import com.gustler.backend.routecatalog.api.RouteReference;
import com.gustler.backend.worker.configuration.CollectionProperties;
import com.gustler.backend.worker.configuration.RouteGeometryProperties;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

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
        verify(catalog).refresh(eq("204000057"), any());
        verify(catalog, never()).refresh(eq("204000155"), any());
        verifyNoInteractions(geometry);
        assertThat(tasks).hasSize(2);
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
        when(catalog.refresh(eq("id-2"), any())).thenThrow(new IllegalStateException("serviceKey=SECRET\nHTTP body"));
        when(catalog.refresh(eq("id-3"), any())).thenReturn(Optional.of(new RouteReference(3)));
        RouteCatalogRefreshScheduler scheduler = new RouteCatalogRefreshScheduler(
            new CollectionProperties(true, List.of("id-1", "id-2", "id-3")), catalog, Clock.systemUTC(),
            mock(RefreshRouteGeometry.class), Runnable::run, new RouteGeometryProperties(false, List.of()));
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
}
