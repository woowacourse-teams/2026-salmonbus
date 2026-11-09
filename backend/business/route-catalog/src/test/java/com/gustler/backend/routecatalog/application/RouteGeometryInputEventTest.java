package com.gustler.backend.routecatalog.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.gustler.backend.quota.api.ApiCallQuota;
import com.gustler.backend.routecatalog.api.RefreshRouteGeometry.Outcome;
import com.gustler.backend.routecatalog.api.RouteGeometryInputAvailable;
import com.gustler.backend.routecatalog.domain.geometry.RouteGeometrySource;
import com.gustler.backend.routecatalog.domain.geometry.RouteGeometryStore;
import com.gustler.backend.routecatalog.domain.geometry.RouteShape;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class RouteGeometryInputEventTest {
    private static final String ROUTE="204000057";
    private final RouteGeometryStore store=mock(RouteGeometryStore.class);
    private final RouteGeometrySource source=mock(RouteGeometrySource.class);
    private final ApiCallQuota quota=mock(ApiCallQuota.class);
    private final ApplicationEventPublisher events=mock(ApplicationEventPublisher.class);
    private final OffsetDateTime at=OffsetDateTime.parse("2026-11-04T12:00:00Z");
    private final RouteGeometryStore.Reference reference=new RouteGeometryStore.Reference(7,ROUTE,12);
    private final RouteGeometryRefresher refresher=new RouteGeometryRefresher(store,source,quota,List.of(),Optional.empty(),events);

    @BeforeEach void sourceReady() {
        when(store.current(ROUTE)).thenReturn(Optional.of(reference));
        when(quota.reserveRouteCatalog(at,1)).thenReturn(true);
        when(source.read(ROUTE)).thenReturn(Optional.of(new RouteGeometrySource.Read(
            new RouteShape(List.of(new RouteShape.Point(127,37),new RouteShape.Point(127.1,37.1))),at)));
    }
    @AfterEach void clearTransaction() {
        if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    @ParameterizedTest @EnumSource(value=RouteGeometryStore.Publication.class,names={"UPDATED","UNCHANGED"})
    void 성공한_현재_입력이_돌아온_뒤에_생성_깨우기_이벤트를_발행한다(RouteGeometryStore.Publication publication) {
        when(store.publish(any(),any())).thenReturn(publication);
        assertThat(refresher.refresh(ROUTE,at)).isEqualTo(Outcome.valueOf(publication.name()));
        var order=inOrder(store,events);
        order.verify(store).current(ROUTE);order.verify(store).publish(any(),any());
        order.verify(events).publishEvent(new RouteGeometryInputAvailable(ROUTE,12));
        verifyNoMoreInteractions(events);
    }
    @Test void 판본이_바뀌거나_입력을_발행하지_못하면_이벤트가_없다() {
        when(store.publish(any(),any())).thenReturn(RouteGeometryStore.Publication.REFERENCE_CHANGED);
        assertThat(refresher.refresh(ROUTE,at)).isEqualTo(Outcome.REFERENCE_CHANGED);
        when(source.read(ROUTE)).thenReturn(Optional.empty());
        assertThat(refresher.refresh(ROUTE,at)).isEqualTo(Outcome.SOURCE_UNAVAILABLE);
        when(quota.reserveRouteCatalog(at,1)).thenReturn(false);
        assertThat(refresher.refresh(ROUTE,at)).isEqualTo(Outcome.NO_QUOTA);
        when(store.current(ROUTE)).thenReturn(Optional.empty());
        assertThat(refresher.refresh(ROUTE,at)).isEqualTo(Outcome.NO_REFERENCE);
        verifyNoInteractions(events);
    }
    @Test void 저장_예외는_그대로_전파하고_이벤트를_발행하지_않는다() {
        var failure=new IllegalStateException("save failed");when(store.publish(any(),any())).thenThrow(failure);
        assertThatThrownBy(()->refresher.refresh(ROUTE,at)).isSameAs(failure);verifyNoInteractions(events);
    }
    @Test void 상위_트랜잭션의_커밋까지_발행을_지연한다() {
        TransactionSynchronizationManager.initSynchronization();TransactionSynchronizationManager.setActualTransactionActive(true);
        when(store.publish(any(),any())).thenReturn(RouteGeometryStore.Publication.UPDATED);
        assertThat(refresher.refresh(ROUTE,at)).isEqualTo(Outcome.UPDATED);verifyNoInteractions(events);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(events).publishEvent(new RouteGeometryInputAvailable(ROUTE,12));
    }
    @Test void 상위_트랜잭션이_롤백되면_생성을_깨우지_않는다() {
        TransactionSynchronizationManager.initSynchronization();TransactionSynchronizationManager.setActualTransactionActive(true);
        when(store.publish(any(),any())).thenReturn(RouteGeometryStore.Publication.UNCHANGED);
        refresher.refresh(ROUTE,at);
        TransactionSynchronizationManager.getSynchronizations().forEach(s->s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verifyNoInteractions(events);
    }
    @Test void 알림_실패가_이미_발행된_입력의_성공을_뒤집지_않는다() {
        when(store.publish(any(),any())).thenReturn(RouteGeometryStore.Publication.UPDATED);
        doThrow(new IllegalStateException("notification failed")).when(events).publishEvent(any(Object.class));
        assertThat(refresher.refresh(ROUTE,at)).isEqualTo(Outcome.UPDATED);
    }
}
