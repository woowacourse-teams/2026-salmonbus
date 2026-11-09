package com.gustler.backend.observations.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.gustler.backend.observations.api.ObservationRouteMismatchDetected;
import com.gustler.backend.observations.domain.ObservationBatchReservation;
import com.gustler.backend.observations.domain.ObservationReply;
import com.gustler.backend.observations.domain.ObservationResponse;
import com.gustler.backend.observations.domain.ObservationSource;
import com.gustler.backend.quota.api.ApiCallQuota;
import com.gustler.backend.routecatalog.api.CurrentRouteVersion;
import com.gustler.backend.routecatalog.api.RouteReference;
import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ObservationRouteMismatchEventTest {
    private static final String ROUTE="204000057";
    private final CurrentRouteVersion routes=mock(CurrentRouteVersion.class);
    private final ObservationBatchLedger ledger=mock(ObservationBatchLedger.class);
    private final ApiCallQuota quota=mock(ApiCallQuota.class);
    private final ObservationSource source=mock(ObservationSource.class);
    private final ApplicationEventPublisher events=mock(ApplicationEventPublisher.class);
    private final Clock clock=Clock.fixed(Instant.parse("2026-11-04T01:00:00Z"),ZoneOffset.UTC);
    private final ObservationCollector collector=new ObservationCollector(routes,ledger,quota,source,clock,events);

    @BeforeEach void reserve() {
        when(routes.currentVersionOf(eq(ROUTE),any())).thenReturn(Optional.of(new RouteReference(7)));
        when(ledger.reserve(any(),any())).thenReturn(new ObservationBatchReservation(10,true,"a"));
        when(ledger.markDispatching(anyLong(),any(),any(),anyString())).thenReturn(true);
        when(source.read(eq(ROUTE),eq("a"))).thenReturn(ObservationResponse::unconfirmed);
    }
    @AfterEach void clearTransaction() {
        if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    private static PSQLException foreignKey(String state,String constraint) {
        return new PSQLException(new ServerErrorMessage("SERROR\0C"+state+
            "\0Mviolates foreign key constraint \"fk_observation_route_stop\"\0n"+constraint+"\0\0"));
    }
    private RuntimeException failSave(SQLException sql) {
        var original=new DataIntegrityViolationException("save failed",sql);
        when(ledger.conclude(anyLong(),any(),any())).thenAnswer(call->{
            call.<ObservationReply>getArgument(1).interpret(call.getArgument(2));throw original;
        });
        return original;
    }
    @Test void 정확한_정류장_FK만_저장경계가_실패한_뒤_한번_알린다() {
        var batch=new BatchUpdateException("batch failed","23503",new int[0]);
        batch.setNextException(foreignKey("23503","fk_observation_route_stop"));
        var original=failSave(batch);
        assertThatThrownBy(()->collector.collectOnce(ROUTE)).isSameAs(original);
        var order=inOrder(ledger,events);order.verify(ledger).reserve(any(),any());
        order.verify(ledger).markDispatching(anyLong(),any(),any(),anyString());
        order.verify(ledger).conclude(anyLong(),any(),any());
        order.verify(events).publishEvent(new ObservationRouteMismatchDetected(ROUTE,7));
        verifyNoMoreInteractions(events);verify(ledger,times(1)).conclude(anyLong(),any(),any());
    }
    @ParameterizedTest @CsvSource({"23503,fk_other","23505,fk_observation_route_stop","23503,fk_observation_route_stop_extra"})
    void SQLState와_구조화된_제약이_함께_맞지_않으면_일반_실패로_둔다(String state,String constraint) {
        var original=failSave(foreignKey(state,constraint));
        assertThatThrownBy(()->collector.collectOnce(ROUTE)).isSameAs(original);verifyNoInteractions(events);
    }
    @Test void 메시지에_이름만_들어간_일반_SQLException을_정류장_불일치로_추측하지_않는다() {
        var original=failSave(new SQLException("fk_observation_route_stop","23503"));
        assertThatThrownBy(()->collector.collectOnce(ROUTE)).isSameAs(original);verifyNoInteractions(events);
    }
    @Test void HTTP_해석_중_예외는_정류장_불일치_이벤트를_발행하지_않는다() {
        var original=new DataIntegrityViolationException("parse failed",foreignKey("23503","fk_observation_route_stop"));
        when(source.read(eq(ROUTE),eq("a"))).thenReturn(at->{throw original;});
        when(ledger.conclude(anyLong(),any(),any())).thenAnswer(call->
            call.<ObservationReply>getArgument(1).interpret(call.getArgument(2)));
        assertThatThrownBy(()->collector.collectOnce(ROUTE)).isSameAs(original);verifyNoInteractions(events);
    }
    @Test void HTTP_호출_실패는_저장_불일치로_발행하지_않는다() {
        var original=new DataIntegrityViolationException("upstream failed",foreignKey("23503","fk_observation_route_stop"));
        when(source.read(eq(ROUTE),eq("a"))).thenThrow(original);
        assertThatThrownBy(()->collector.collectOnce(ROUTE)).isSameAs(original);
        verify(ledger,never()).conclude(anyLong(),any(),any());verifyNoInteractions(events);
    }
    @Test void 알림_수신자_실패가_원래_저장_예외를_덮지_않는다() {
        var original=failSave(foreignKey("23503","fk_observation_route_stop"));
        doThrow(new IllegalStateException("notification failed")).when(events).publishEvent(any(Object.class));
        assertThatThrownBy(()->collector.collectOnce(ROUTE)).isSameAs(original);
        verify(events).publishEvent(new ObservationRouteMismatchDetected(ROUTE,7));
    }
    @Test void 상위_트랜잭션이_있으면_롤백_완료까지_발행하지_않는다() {
        TransactionSynchronizationManager.initSynchronization();TransactionSynchronizationManager.setActualTransactionActive(true);
        var original=failSave(foreignKey("23503","fk_observation_route_stop"));
        assertThatThrownBy(()->collector.collectOnce(ROUTE)).isSameAs(original);verifyNoInteractions(events);
        TransactionSynchronizationManager.getSynchronizations().forEach(s->s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verify(events).publishEvent(new ObservationRouteMismatchDetected(ROUTE,7));
    }
    @ParameterizedTest @ValueSource(ints={TransactionSynchronization.STATUS_COMMITTED,TransactionSynchronization.STATUS_UNKNOWN})
    void 롤백을_확인하지_못한_트랜잭션에서는_불일치_이벤트가_없다(int completion) {
        TransactionSynchronizationManager.initSynchronization();TransactionSynchronizationManager.setActualTransactionActive(true);
        var original=failSave(foreignKey("23503","fk_observation_route_stop"));
        assertThatThrownBy(()->collector.collectOnce(ROUTE)).isSameAs(original);
        TransactionSynchronizationManager.getSynchronizations().forEach(s->s.afterCompletion(completion));verifyNoInteractions(events);
    }
    @Test void SQLException_next_chain의_순환도_무한_탐색하지_않는다() {
        var first=new SQLException("first","23503");var second=foreignKey("23503","fk_other");
        first.setNextException(second);second.setNextException(first);
        var original=failSave(first);
        assertThatThrownBy(()->collector.collectOnce(ROUTE)).isSameAs(original);verifyNoInteractions(events);
    }
}
