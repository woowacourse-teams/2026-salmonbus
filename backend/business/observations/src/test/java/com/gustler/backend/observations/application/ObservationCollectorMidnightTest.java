package com.gustler.backend.observations.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import com.gustler.backend.gbis.api.GbisLocationResult;
import com.gustler.backend.observations.domain.CollectionPreparation;
import com.gustler.backend.observations.domain.CollectionResult;
import com.gustler.backend.observations.domain.ObservationResponse;
import com.gustler.backend.observations.infrastructure.gbis.GbisObservationClient;
import com.gustler.backend.observations.infrastructure.gbis.GbisObservationMapper;
import com.gustler.backend.quota.api.ApiCallQuota;
import com.gustler.backend.routecatalog.api.CurrentRouteVersion;
import com.gustler.backend.routecatalog.api.RouteReference;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

class ObservationCollectorMidnightTest {
    private static final String ROUTE_ID = "204000057";
    private static final OffsetDateTime PREPARED_AT = OffsetDateTime.parse("2026-11-22T14:59:59Z");
    private static final OffsetDateTime DISPATCH_AT = PREPARED_AT.plusSeconds(1);

    @Test
    void 준비_직후_한국_자정을_넘고_새_날짜의_한도가_없으면_조회하지_않는다() {
        // given
        CurrentRouteVersion routes = mock(CurrentRouteVersion.class);
        CollectionBatchService ledger = mock(CollectionBatchService.class);
        ApiCallQuota quota = mock(ApiCallQuota.class);
        GbisObservationClient source = mock(GbisObservationClient.class);
        Clock clock = mock(Clock.class);
        given(clock.getZone()).willReturn(ZoneOffset.UTC);
        given(clock.instant()).willReturn(PREPARED_AT.toInstant(), PREPARED_AT.toInstant(), DISPATCH_AT.toInstant());
        given(routes.findCurrentVersion(ROUTE_ID)).willReturn(Optional.of(new RouteReference(7)));
        given(ledger.prepare(any(), any())).willReturn(new CollectionPreparation(10, true, "b"));
        given(quota.ensureLocationReservation("b", PREPARED_AT, DISPATCH_AT)).willReturn(false);
        ObservationCollector collector = new ObservationCollector(
                routes,
                ledger,
                quota,
                source,
                clock,
                mock(ApplicationEventPublisher.class)
        );

        // when
        collector.collectOnce(ROUTE_ID);

        // then
        then(quota).should().ensureLocationReservation("b", PREPARED_AT, DISPATCH_AT);
        then(ledger).should().abandonBeforeSend(10);
        then(source).shouldHaveNoInteractions();
    }

    @Test
    void 같은_한국_날짜면_한도를_다시_예약하지_않는다() {
        // given
        CurrentRouteVersion routes = mock(CurrentRouteVersion.class);
        CollectionBatchService ledger = mock(CollectionBatchService.class);
        ApiCallQuota quota = mock(ApiCallQuota.class);
        GbisObservationClient source = mock(GbisObservationClient.class);
        Clock clock = Clock.fixed(PREPARED_AT.toInstant(), ZoneOffset.UTC);
        given(routes.findCurrentVersion(ROUTE_ID)).willReturn(Optional.of(new RouteReference(7)));
        given(ledger.prepare(any(), any())).willReturn(new CollectionPreparation(10, true, "b"));
        given(source.read(ROUTE_ID, "b")).willReturn(CollectionResult::unconfirmed);
        given(ledger.conclude(anyLong(), any(), any())).willReturn(CollectionResult.unconfirmed(PREPARED_AT));
        ObservationCollector collector = new ObservationCollector(
                routes,
                ledger,
                quota,
                source,
                clock,
                mock(ApplicationEventPublisher.class)
        );

        // when
        collector.collectOnce(ROUTE_ID);

        // then
        then(quota).shouldHaveNoInteractions();
        then(source).should().read(ROUTE_ID, "b");
    }

    @Test
    void 자정_후_확보한_키가_거절되면_새_날짜의_같은_키를_제외한다() {
        // given
        CurrentRouteVersion routes = mock(CurrentRouteVersion.class);
        CollectionBatchService ledger = mock(CollectionBatchService.class);
        ApiCallQuota quota = mock(ApiCallQuota.class);
        GbisObservationClient source = mock(GbisObservationClient.class);
        Clock clock = mock(Clock.class);
        given(clock.getZone()).willReturn(ZoneOffset.UTC);
        given(clock.instant()).willReturn(PREPARED_AT.toInstant(), PREPARED_AT.toInstant(), DISPATCH_AT.toInstant());
        given(routes.findCurrentVersion(ROUTE_ID)).willReturn(Optional.of(new RouteReference(7)));
        given(ledger.prepare(any(), any())).willReturn(new CollectionPreparation(10, true, "b"));
        given(quota.ensureLocationReservation("b", PREPARED_AT, DISPATCH_AT)).willReturn(true);
        given(source.read(ROUTE_ID, "b")).willReturn(receivedAt ->
            GbisObservationMapper.response(new GbisLocationResult.DailyQuotaExceeded(), receivedAt));
        given(ledger.conclude(anyLong(), any(), any())).willAnswer(call ->
            call.<ObservationResponse>getArgument(1).interpret(call.getArgument(2)));
        ObservationCollector collector = new ObservationCollector(
                routes,
                ledger,
                quota,
                source,
                clock,
                mock(ApplicationEventPublisher.class)
        );

        // when
        collector.collectOnce(ROUTE_ID);

        // then
        InOrder order = inOrder(quota, source);
        then(quota).should(order).ensureLocationReservation("b", PREPARED_AT, DISPATCH_AT);
        then(source).should(order).read(ROUTE_ID, "b");
        then(quota).should(order).excludeLocationKey("b", DISPATCH_AT);
    }
}
