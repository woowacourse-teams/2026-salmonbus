package com.gustler.backend.api.favorite.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gustler.backend.api.favorite.FavoriteTargetAmbiguousException;
import com.gustler.backend.api.favorite.FavoriteTargetUnavailableException;
import com.gustler.backend.api.favorite.domain.FavoriteDirection;
import com.gustler.backend.api.favorite.domain.FavoriteTarget;
import com.gustler.backend.api.route.application.RouteGeometryQueryRepository;
import com.gustler.backend.api.route.domain.RouteGeometry;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FavoriteTargetResolverTest {
    private static final FavoriteTarget TARGET = new FavoriteTarget("204000057", "277102322", FavoriteDirection.UP);
    private final RouteGeometryQueryRepository routes = mock(RouteGeometryQueryRepository.class);
    private final FavoriteTargetResolver resolver = new FavoriteTargetResolver(routes);

    @Test
    void 현재_판본의_유일한_승차_방문을_찾는다() {
        routeStops(new RouteGeometry.Stop(TARGET.stopId(), 1, "강남역", "UP", true, null));

        assertThat(resolver.requireAvailable(TARGET)).isEqualTo(TARGET);
    }

    @Test
    void 방문이_없으면_등록하지_않는다() {
        routeStops();

        assertThatThrownBy(() -> resolver.requireAvailable(TARGET))
            .isInstanceOf(FavoriteTargetUnavailableException.class);
    }

    @Test
    void 같은_정류장의_다른_방향은_선택한_방문으로_구분한다() {
        routeStops(
            new RouteGeometry.Stop(TARGET.stopId(), 1, "강남역", "UP", true, null),
            new RouteGeometry.Stop(TARGET.stopId(), 9, "강남역", "DOWN", true, null));

        assertThat(resolver.requireAvailable(TARGET)).isEqualTo(TARGET);
    }

    @Test
    void 같은_방향의_승차_방문이_여러_개면_임의로_고르지_않는다() {
        routeStops(
            new RouteGeometry.Stop(TARGET.stopId(), 1, "강남역", "UP", true, null),
            new RouteGeometry.Stop(TARGET.stopId(), 9, "강남역", "UP", true, null));

        assertThatThrownBy(() -> resolver.requireAvailable(TARGET))
            .isInstanceOf(FavoriteTargetAmbiguousException.class);
    }

    private void routeStops(RouteGeometry.Stop... stops) {
        when(routes.reference(TARGET.routeId()))
            .thenReturn(Optional.of(new RouteGeometryQueryRepository.Reference(
                TARGET.routeId(),
                12L,
                "노선",
                "기점",
                "종점",
                null
            )));
        when(routes.stops(12L)).thenReturn(List.of(stops));
    }
}
