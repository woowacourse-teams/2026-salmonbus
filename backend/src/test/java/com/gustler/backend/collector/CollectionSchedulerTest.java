package com.gustler.backend.collector;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CollectionSchedulerTest {

    private static final String ROUTE_3330 = "204000057";
    private static final String ROUTE_9100 = "234000016";

    @Mock
    private CollectionProperties properties;

    @Mock
    private ObservationCollector collector;

    @InjectMocks
    private CollectionScheduler scheduler;

    @Test
    void 설정에_적힌_노선을_모두_돈다() {
        // given
        given(properties.routeIds()).willReturn(List.of(ROUTE_3330, ROUTE_9100));

        // when
        scheduler.collectAllRoutes();

        // then
        then(collector).should().collectOnce(ROUTE_9100);
    }

    @Test
    void 한_노선이_터져도_다음_노선을_이어서_돈다() {
        // given
        given(properties.routeIds()).willReturn(List.of(ROUTE_3330, ROUTE_9100));
        willThrow(new IllegalStateException("상류가 터졌다")).given(collector).collectOnce(ROUTE_3330);

        // when
        scheduler.collectAllRoutes();

        // then
        then(collector).should().collectOnce(ROUTE_9100);
    }

    @Test
    void 돌_노선이_없으면_아무것도_부르지_않는다() {
        // given
        given(properties.routeIds()).willReturn(List.of());

        // when
        scheduler.collectAllRoutes();

        // then
        then(collector).shouldHaveNoInteractions();
    }
}
