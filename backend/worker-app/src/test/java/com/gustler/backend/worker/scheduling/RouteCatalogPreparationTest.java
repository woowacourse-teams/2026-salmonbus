package com.gustler.backend.worker.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

import com.gustler.backend.routecatalog.api.RefreshRouteCatalog;
import com.gustler.backend.routecatalog.api.RefreshRouteGeometry;
import com.gustler.backend.routecatalog.api.RouteCatalogPreparationRequested;
import com.gustler.backend.routecatalog.api.RouteReference;
import com.gustler.backend.worker.configuration.CollectionProperties;
import com.gustler.backend.worker.configuration.RouteGeometryProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RouteCatalogPreparationTest {
    private static final String ROUTE_ID = "204000057";

    private final RefreshRouteCatalog catalog = mock(RefreshRouteCatalog.class);
    private final RefreshRouteGeometry geometry = mock(RefreshRouteGeometry.class);
    private final JdbcRouteRefreshStateRepository states = mock(JdbcRouteRefreshStateRepository.class);
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

    @Test
    void 중복된_준비_요청은_모아서_별도_실행기에서_한_번_처리한다() {
        // given
        given(states.findBySourceId(ROUTE_ID)).willReturn(Optional.empty());
        given(catalog.refresh(eq(ROUTE_ID), any())).willReturn(Optional.of(new RouteReference(7)));
        RouteCatalogAndGeometryRefresher coordinator = create(Clock.systemUTC());

        // when
        coordinator.catalogPreparationRequested(new RouteCatalogPreparationRequested(ROUTE_ID));
        coordinator.catalogPreparationRequested(new RouteCatalogPreparationRequested(ROUTE_ID));

        // then
        assertThat(tasks).hasSize(1);
        then(catalog).shouldHaveNoInteractions();
        then(states).shouldHaveNoInteractions();

        // when
        tasks.remove().run();

        // then
        then(catalog).should(times(1)).refresh(eq(ROUTE_ID), any());
        then(geometry).shouldHaveNoInteractions();
    }

    @Test
    void 이미_준비된_노선의_늦은_요청은_외부_정보를_다시_받지_않는다() {
        // given
        given(states.findBySourceId(ROUTE_ID)).willReturn(Optional.of(
            new JdbcRouteRefreshStateRepository.State(
                    1,
                    ROUTE_ID,
                    ROUTE_ID,
                    7,
                    true
            )));
        RouteCatalogAndGeometryRefresher coordinator = create(Clock.systemUTC());

        // when
        coordinator.catalogPreparationRequested(new RouteCatalogPreparationRequested(ROUTE_ID));
        tasks.remove().run();

        // then
        then(catalog).shouldHaveNoInteractions();
        then(geometry).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 처리중_중복은_자동재시도_한도를_늘리지_않고_종료후_새_회차는_준비를_요청할_수_있다(
        final boolean duplicateDuringLastAttempt
    ) {
        // given
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-23T00:00:00Z"));
        RouteCatalogAndGeometryRefresher coordinator = create(clock(now));
        AtomicInteger calls = new AtomicInteger();
        given(states.findBySourceId(ROUTE_ID)).willReturn(Optional.empty());
        given(catalog.refresh(eq(ROUTE_ID), any())).willAnswer(call -> {
            if (calls.incrementAndGet() == 5 && duplicateDuringLastAttempt) {
                coordinator.catalogPreparationRequested(new RouteCatalogPreparationRequested(ROUTE_ID));
            }
            return Optional.empty();
        });

        // when
        coordinator.catalogPreparationRequested(new RouteCatalogPreparationRequested(ROUTE_ID));
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(tasks).hasSize(1);
            tasks.remove().run();
            now.updateAndGet(value -> value.plus(Duration.ofMinutes(10)));
            coordinator.retryPending();
        }

        // then
        assertThat(tasks).isEmpty();
        then(catalog).should(times(5)).refresh(eq(ROUTE_ID), any());

        // when
        coordinator.catalogPreparationRequested(new RouteCatalogPreparationRequested(ROUTE_ID));
        tasks.remove().run();

        // then
        then(catalog).should(times(6)).refresh(eq(ROUTE_ID), any());
        then(geometry).shouldHaveNoInteractions();
    }

    private RouteCatalogAndGeometryRefresher create(Clock clock) {
        RouteCatalogAndGeometryRefresher coordinator = new RouteCatalogAndGeometryRefresher(
                catalog,
                geometry,
                states,
                tasks::add,
                new CollectionProperties(true, List.of(ROUTE_ID)),
                new RouteGeometryProperties(false, List.of()),
                Optional.of(clock),
                Duration.ofMinutes(1)
        );
        coordinator.applicationReady();
        return coordinator;
    }

    private Clock clock(AtomicReference<Instant> now) {
        Clock clock = mock(Clock.class);
        given(clock.instant()).willAnswer(call -> now.get());
        given(clock.getZone()).willReturn(ZoneOffset.UTC);
        return clock;
    }
}
