package com.gustler.backend.forecasting.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gustler.backend.forecasting.application.evaluation.ObservationDeliveryReceiver;
import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.application.quality.WorkerQualityState;
import com.gustler.backend.forecasting.application.quality.WorkerQualityState.Snapshot;
import com.gustler.backend.forecasting.application.quality.WorkerQualityTransactions;
import com.gustler.backend.forecasting.infrastructure.observations.ObservationDeliveryAdapter;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import com.gustler.backend.observations.api.ObservationDeliveryBatch;
import com.gustler.backend.observations.api.ObservationDeliveryBatch.Kind;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 실제 트랜잭션 커밋 순서를 검증한다. 관측 저장은 기존 수집 통합 테스트에서 검증한다. */
@ForecastingIntegrationTest
class WorkerQualityDeliveryTest {
    private final ObservationDeliveryReceiver receiver = mock(ObservationDeliveryReceiver.class);
    private final RouteDataQualityAccess quality = mock(RouteDataQualityAccess.class);
    @Autowired
    PlatformTransactionManager transactions;

    @SuppressWarnings("unchecked")
    private ObservationDeliveryAdapter adapter(WorkerQualityState state) {
        ObjectProvider<ObservationDeliveryReceiver> receivers = mock(ObjectProvider.class);
        when(receivers.getIfAvailable()).thenReturn(receiver);
        ObservationDeliveryAdapter adapter = new ObservationDeliveryAdapter(quality, receivers);
        if (state != null) {
            adapter.useQualityMemory(state);
        }
        return adapter;
    }

    private ObservationDeliveryBatch batch() {
        return new ObservationDeliveryBatch(1, 1, 1, Kind.STORED,
            Instant.parse("2026-10-01T00:00:00Z"), List.of());
    }

    @Test
    void 품질이_그대로면_수집_커밋마다_버전을_다시_조회하지_않는다() {
        // given
        AtomicInteger reads = new AtomicInteger();
        ObservationDeliveryAdapter adapter = adapter(new WorkerQualityState(version -> {
            reads.incrementAndGet();
            return new Snapshot(1, Set.of());
        }, 20));
        ObservationDeliveryBatch batch = batch();

        // when
        for (int i = 0; i < 3; i++) {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                adapter.record(batch);
            });
        }

        // then
        assertThat(reads.get()).isEqualTo(1);
        verify(receiver, times(3)).receive(1, batch);
        verifyNoInteractions(quality);
    }

    @Test
    void 관측_저장_후_캐시에_넣기_전에_품질이_바뀌면_해당_관측을_캐시에_넣지_않는다() {
        // given
        WorkerQualityState state = new WorkerQualityState(version -> new Snapshot(1, Set.of()), 20);
        ObservationDeliveryAdapter adapter = adapter(state);
        WorkerQualityTransactions changes = new WorkerQualityTransactions(state);

        // when
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            adapter.record(batch());
            changes.changing();
        });

        // then
        verifyNoInteractions(receiver);
    }

    @Test
    void 수집이_롤백되면_관측을_캐시에_넣지_않는다() {
        // given
        ObservationDeliveryAdapter adapter = adapter(new WorkerQualityState(version -> new Snapshot(1, Set.of()), 20));

        // when
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            adapter.record(batch());
            status.setRollbackOnly();
        });

        // then
        verifyNoInteractions(receiver);
    }

    @Test
    void 재시작_후_DB에서_조사_중임을_확인하면_수집_원본을_캐시에_넣지_않는다() {
        // given
        ObservationDeliveryAdapter adapter =
            adapter(new WorkerQualityState(version -> new Snapshot(2, Set.of("bus-A")), 20));

        // when
        new TransactionTemplate(transactions).executeWithoutResult(status -> adapter.record(batch()));

        // then
        verifyNoInteractions(receiver);
    }

    @Test
    void 품질_정보의_메모리_보관을_끄면_기존처럼_DB에서_조회한다() {
        // given
        ObservationDeliveryAdapter adapter = adapter(null);
        ObservationDeliveryBatch batch = batch();
        when(quality.current(1)).thenReturn(3L);

        // when
        new TransactionTemplate(transactions).executeWithoutResult(status -> adapter.record(batch));

        // then
        verify(quality).current(1);
        verify(receiver).receive(3, batch);
    }
}
