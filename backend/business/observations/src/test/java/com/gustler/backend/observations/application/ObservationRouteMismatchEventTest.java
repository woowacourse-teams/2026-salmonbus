package com.gustler.backend.observations.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.gustler.backend.diagnostics.WorkerOperationLog;
import com.gustler.backend.observations.api.ObservationRouteStopMismatchDetected;
import com.gustler.backend.observations.domain.CollectionPreparation;
import com.gustler.backend.observations.domain.CollectionResult;
import com.gustler.backend.observations.domain.ObservationResponse;
import com.gustler.backend.observations.infrastructure.gbis.GbisObservationClient;
import com.gustler.backend.quota.api.ApiCallQuota;
import com.gustler.backend.routecatalog.api.CurrentRouteVersion;
import com.gustler.backend.routecatalog.api.RouteReference;
import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ObservationRouteMismatchEventTest {

    private static final String ROUTE = "204000057";

    private final CurrentRouteVersion routes = mock(CurrentRouteVersion.class);
    private final CollectionBatchService ledger = mock(CollectionBatchService.class);
    private final ApiCallQuota quota = mock(ApiCallQuota.class);
    private final GbisObservationClient source = mock(GbisObservationClient.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-04T01:00:00Z"), ZoneOffset.UTC);
    private final ObservationCollector collector = new ObservationCollector(
            routes,
            ledger,
            quota,
            source,
            clock,
            events
    );

    @BeforeEach
    void 수집_입력을_준비한다() {
        given(routes.findCurrentVersion(ROUTE)).willReturn(Optional.of(new RouteReference(7)));
        given(ledger.prepare(any(), any())).willReturn(new CollectionPreparation(10, true, "a"));
        given(source.read(ROUTE, "a")).willReturn(CollectionResult::unconfirmed);
    }

    @AfterEach
    void 트랜잭션_동기화를_정리한다() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private static PSQLException foreignKey(String state, String constraint) {
        return new PSQLException(new ServerErrorMessage("SERROR\0C" + state
            + "\0Mviolates foreign key constraint \"fk_observation_route_stop\"\0n" + constraint + "\0\0"));
    }

    private RuntimeException failSave(SQLException sql) {
        DataIntegrityViolationException original = new DataIntegrityViolationException("save failed", sql);
        given(ledger.conclude(anyLong(), any(), any())).willAnswer(call -> {
            call.<ObservationResponse>getArgument(1).interpret(call.getArgument(2));
            throw original;
        });
        return original;
    }

    @Test
    void 저장_실패_계측은_노선_갱신_알림을_처리한_뒤_끝난다() {
        // given
        RuntimeException original = failSave(foreignKey("23503", "fk_observation_route_stop"));
        List<String> completedSteps = new ArrayList<>();
        willAnswer(invocation -> {
            completedSteps.add("ROUTE_REFRESH");
            return null;
        }).given(events).publishEvent(new ObservationRouteStopMismatchDetected(ROUTE, 7));
        WorkerOperationLog.Listener operationListener = (operation, route, nanos, failed) -> {
            if ("collection_save_and_commit".equals(operation)) {
                completedSteps.add("SAVE_MEASURED");
            }
        };
        WorkerOperationLog.setListener(operationListener);

        try {
            // when
            Throwable caught = catchThrowable(() -> collector.collectOnce(ROUTE));

            // then
            assertThat(caught).isSameAs(original);
            assertThat(completedSteps).containsExactly("ROUTE_REFRESH", "SAVE_MEASURED");
        } finally {
            WorkerOperationLog.clearListener(operationListener);
        }
    }

    @Test
    void 정확한_정류장_FK만_저장경계가_실패한_뒤_한번_알린다() {
        // given
        BatchUpdateException batch = new BatchUpdateException("batch failed", "23503", new int[0]);
        batch.setNextException(foreignKey("23503", "fk_observation_route_stop"));
        RuntimeException original = failSave(batch);

        // when
        Throwable caught = catchThrowable(() -> collector.collectOnce(ROUTE));

        // then
        assertThat(caught).isSameAs(original);
        InOrder order = inOrder(ledger, events);
        then(ledger).should(order).prepare(any(), any());
        then(ledger).should(order).conclude(anyLong(), any(), any());
        then(events).should(order).publishEvent(new ObservationRouteStopMismatchDetected(ROUTE, 7));
        then(events).shouldHaveNoMoreInteractions();
        then(ledger).should(times(1)).conclude(anyLong(), any(), any());
    }

    @ParameterizedTest
    @CsvSource({"23503,fk_other", "23505,fk_observation_route_stop", "23503,fk_observation_route_stop_extra"})
    void SQLState와_구조화된_제약이_함께_맞지_않으면_일반_실패로_둔다(String state, String constraint) {
        // given
        RuntimeException original = failSave(foreignKey(state, constraint));

        // when
        Throwable caught = catchThrowable(() -> collector.collectOnce(ROUTE));

        // then
        assertThat(caught).isSameAs(original);
        then(events).shouldHaveNoInteractions();
    }

    @Test
    void 메시지에_이름만_들어간_일반_SQLException을_정류장_불일치로_추측하지_않는다() {
        // given
        RuntimeException original = failSave(new SQLException("fk_observation_route_stop", "23503"));

        // when
        Throwable caught = catchThrowable(() -> collector.collectOnce(ROUTE));

        // then
        assertThat(caught).isSameAs(original);
        then(events).shouldHaveNoInteractions();
    }

    @Test
    void HTTP_해석_중_예외는_정류장_불일치_이벤트를_발행하지_않는다() {
        // given
        DataIntegrityViolationException original = new DataIntegrityViolationException("parse failed",
            foreignKey("23503", "fk_observation_route_stop"));
        given(source.read(ROUTE, "a")).willReturn(at -> {
            throw original;
        });
        given(ledger.conclude(anyLong(), any(), any())).willAnswer(call ->
            call.<ObservationResponse>getArgument(1).interpret(call.getArgument(2)));

        // when
        Throwable caught = catchThrowable(() -> collector.collectOnce(ROUTE));

        // then
        assertThat(caught).isSameAs(original);
        then(events).shouldHaveNoInteractions();
    }

    @Test
    void HTTP_호출_실패는_저장_불일치로_발행하지_않는다() {
        // given
        DataIntegrityViolationException original = new DataIntegrityViolationException("upstream failed",
            foreignKey("23503", "fk_observation_route_stop"));
        given(source.read(ROUTE, "a")).willThrow(original);

        // when
        Throwable caught = catchThrowable(() -> collector.collectOnce(ROUTE));

        // then
        assertThat(caught).isSameAs(original);
        then(ledger).should(never()).conclude(anyLong(), any(), any());
        then(events).shouldHaveNoInteractions();
    }

    @Test
    void 알림_수신자_실패가_원래_저장_예외를_덮지_않는다() {
        // given
        RuntimeException original = failSave(foreignKey("23503", "fk_observation_route_stop"));
        willThrow(new IllegalStateException("notification failed")).given(events).publishEvent(any(Object.class));

        // when
        Throwable caught = catchThrowable(() -> collector.collectOnce(ROUTE));

        // then
        assertThat(caught).isSameAs(original);
        then(events).should().publishEvent(new ObservationRouteStopMismatchDetected(ROUTE, 7));
    }

    @Test
    void 상위_트랜잭션이_있으면_롤백_완료까지_발행하지_않는다() {
        // given
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        RuntimeException original = failSave(foreignKey("23503", "fk_observation_route_stop"));

        // when
        Throwable caught = catchThrowable(() -> collector.collectOnce(ROUTE));

        // then
        assertThat(caught).isSameAs(original);
        then(events).shouldHaveNoInteractions();
        TransactionSynchronizationManager.getSynchronizations()
            .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        then(events).should().publishEvent(new ObservationRouteStopMismatchDetected(ROUTE, 7));
    }

    @ParameterizedTest
    @ValueSource(ints = {TransactionSynchronization.STATUS_COMMITTED, TransactionSynchronization.STATUS_UNKNOWN})
    void 롤백을_확인하지_못한_트랜잭션에서는_불일치_이벤트가_없다(final int completion) {
        // given
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        RuntimeException original = failSave(foreignKey("23503", "fk_observation_route_stop"));

        // when
        Throwable caught = catchThrowable(() -> collector.collectOnce(ROUTE));

        // then
        assertThat(caught).isSameAs(original);
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(completion));
        then(events).shouldHaveNoInteractions();
    }

    @Test
    void SQLException_next_chain의_순환도_무한_탐색하지_않는다() {
        // given
        SQLException first = new SQLException("first", "23503");
        PSQLException second = foreignKey("23503", "fk_other");
        first.setNextException(second);
        second.setNextException(first);
        RuntimeException original = failSave(first);

        // when
        Throwable caught = catchThrowable(() -> collector.collectOnce(ROUTE));

        // then
        assertThat(caught).isSameAs(original);
        then(events).shouldHaveNoInteractions();
    }
}
