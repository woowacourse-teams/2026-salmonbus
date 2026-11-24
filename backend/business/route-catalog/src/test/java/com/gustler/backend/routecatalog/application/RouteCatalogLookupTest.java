package com.gustler.backend.routecatalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import com.gustler.backend.quota.api.ApiCallQuota;
import com.gustler.backend.routecatalog.api.RouteReference;
import com.gustler.backend.routecatalog.domain.CurrentRouteVersionQuery;
import com.gustler.backend.routecatalog.domain.RouteRegistry;
import com.gustler.backend.routecatalog.domain.RouteSource;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

class RouteCatalogLookupTest {
    private static final String ROUTE_ID = "204000057";

    private final CurrentRouteVersionQuery query = mock(CurrentRouteVersionQuery.class);
    private final ApiCallQuota quota = mock(ApiCallQuota.class);
    private final RouteSource source = mock(RouteSource.class);
    private final RouteCatalogLoader loader = new RouteCatalogLoader(
            query,
            quota,
            source,
            mock(RouteRegistry.class),
            mock(RouteVersionLoader.class),
            mock(TransactionTemplate.class)
    );

    @Test
    void 미준비_노선은_조회만_하고_외부_호출과_한도_예약을_하지_않는다() {
        // given
        given(query.findIdOf(ROUTE_ID)).willReturn(OptionalLong.empty());

        // when
        Optional<RouteReference> found = loader.findCurrentVersion(ROUTE_ID);

        // then
        assertThat(found).isEmpty();
        then(quota).shouldHaveNoInteractions();
        then(source).shouldHaveNoInteractions();
    }

    @Test
    void 준비된_노선은_저장된_판본을_반환한다() {
        // given
        given(query.findIdOf(ROUTE_ID)).willReturn(OptionalLong.of(7));

        // when
        Optional<RouteReference> found = loader.findCurrentVersion(ROUTE_ID);

        // then
        assertThat(found).contains(new RouteReference(7));
        then(quota).shouldHaveNoInteractions();
        then(source).shouldHaveNoInteractions();
    }
}
