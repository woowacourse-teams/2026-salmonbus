package com.gustler.backend.api.route.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.gustler.backend.api.route.domain.CurrentRoute;
import com.gustler.backend.api.route.domain.Route;
import com.gustler.backend.api.route.domain.RouteStatus;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RouteQueryServiceTest {

    private static final Route ROUTE = new Route(
        "204000057",
        "3330",
        "도촌동9단지앞",
        "안양역"
    );
    private static final Route NEW_ROUTE = new Route(
        "204000070",
        "9007",
        "새 노선 기점",
        "새 노선 종점"
    );

    @Mock
    private RouteQueryRepository routeQueryRepository;

    private RouteQueryService routeQueryService;

    @BeforeEach
    void setUp() {
        routeQueryService = new RouteQueryService(routeQueryRepository);
        given(routeQueryRepository.findAllCurrentRoutes()).willReturn(List.of(ROUTE));
    }

    @Test
    void 활성_모델이_있어도_현재_판본에_발행된_예보가_없으면_준비_중_상태다() {
        // given
        given(routeQueryRepository.existsActiveModel()).willReturn(true);

        // when
        final RouteOverview actual = routeQueryService.getRouteOverview();

        // then
        assertThat(actual.routes()).containsExactly(new CurrentRoute(ROUTE, RouteStatus.PREPARING));
    }

    @Test
    void 활성_모델이_없으면_모든_노선이_준비_중_상태다() {
        // given
        given(routeQueryRepository.existsActiveModel()).willReturn(false);

        // when
        final RouteOverview actual = routeQueryService.getRouteOverview();

        // then
        assertThat(actual.routes()).containsExactly(new CurrentRoute(ROUTE, RouteStatus.PREPARING));
    }

    @Test
    void 활성_모델이_있고_현재_판본에_발행된_예보가_있으면_예보_준비_상태다() {
        // given
        given(routeQueryRepository.existsActiveModel()).willReturn(true);
        given(routeQueryRepository.findForecastPublishedRouteIds()).willReturn(Set.of("204000057"));

        // when
        final RouteOverview actual = routeQueryService.getRouteOverview();

        // then
        assertThat(actual.routes()).containsExactly(new CurrentRoute(ROUTE, RouteStatus.FORECAST_READY));
    }

    @Test
    void 활성_모델이_없으면_발행된_예보가_있어도_준비_중_상태다() {
        // given
        given(routeQueryRepository.existsActiveModel()).willReturn(false);
        given(routeQueryRepository.findForecastPublishedRouteIds()).willReturn(Set.of("204000057"));

        // when
        final RouteOverview actual = routeQueryService.getRouteOverview();

        // then
        assertThat(actual.routes()).containsExactly(new CurrentRoute(ROUTE, RouteStatus.PREPARING));
    }

    @Test
    void 노선마다_현재_판본의_발행_여부로_상태를_정한다() {
        // given
        given(routeQueryRepository.findAllCurrentRoutes()).willReturn(List.of(ROUTE, NEW_ROUTE));
        given(routeQueryRepository.existsActiveModel()).willReturn(true);
        given(routeQueryRepository.findForecastPublishedRouteIds()).willReturn(Set.of("204000057"));

        // when
        final RouteOverview actual = routeQueryService.getRouteOverview();

        // then
        assertThat(actual.routes()).containsExactly(
            new CurrentRoute(ROUTE, RouteStatus.FORECAST_READY),
            new CurrentRoute(NEW_ROUTE, RouteStatus.PREPARING)
        );
    }
}
