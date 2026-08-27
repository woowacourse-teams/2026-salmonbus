package com.gustler.backend.api.route.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.gustler.backend.api.route.domain.Route;
import com.gustler.backend.api.route.domain.RouteStatus;
import java.util.List;
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

    @Mock
    private RouteQueryRepository routeQueryRepository;

    private RouteQueryService routeQueryService;

    @BeforeEach
    void setUp() {
        routeQueryService = new RouteQueryService(routeQueryRepository);
        given(routeQueryRepository.findAllCurrentRoutes()).willReturn(List.of(ROUTE));
    }

    @Test
    void 활성_모델이_있으면_모든_노선이_예보_준비_상태다() {
        // given
        given(routeQueryRepository.existsActiveModel()).willReturn(true);

        // when
        RouteOverview actual = routeQueryService.getRouteOverview();

        // then
        assertThat(actual.routes()).containsExactly(ROUTE);
        assertThat(actual.status()).isEqualTo(RouteStatus.FORECAST_READY);
    }

    @Test
    void 활성_모델이_없으면_모든_노선이_준비_중_상태다() {
        // given
        given(routeQueryRepository.existsActiveModel()).willReturn(false);

        // when
        RouteOverview actual = routeQueryService.getRouteOverview();

        // then
        assertThat(actual.routes()).containsExactly(ROUTE);
        assertThat(actual.status()).isEqualTo(RouteStatus.PREPARING);
    }
}
