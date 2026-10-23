package com.gustler.backend.forecasting.application.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gustler.backend.forecasting.api.ForecastPolicy;
import com.gustler.backend.forecasting.application.evaluation.EvaluationWorkStore.Key;
import com.gustler.backend.forecasting.application.evaluation.EvaluationWorkStore.Restored;
import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalCandidate;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabel;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabel.Lost;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabel.SeatMissing;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabel.Settled;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabel.Skipped;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalObservationQuery;
import com.gustler.backend.forecasting.domain.evaluation.ForecastEvaluation;
import com.gustler.backend.forecasting.domain.evaluation.PendingForecast;
import com.gustler.backend.forecasting.domain.model.ObservedVehicle;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class EvaluateForecastsServiceTest {
    /** 혼잡도가 없는 관측으로, 라벨 회수에는 혼잡도를 사용하지 않는다. */
    private static final Integer CROWD_LEVEL_UNKNOWN = null;

    private static final long ROUTE_VERSION_3330 = 1L;
    private static final String VEHICLE_ID = "204000206";
    private static final Instant OBSERVED_AT = Instant.parse("2026-08-25T08:30:00Z");
    private static final Instant SETTLED_AT = OBSERVED_AT.plusSeconds(600);
    private static final int TARGET_STOP_ORDER = 44;
    private static final long ARRIVAL_OBSERVATION_ID = 7700L;

    @Mock
    private EvaluationWorkStore work;

    @Mock
    private RouteDataQualityAccess quality;

    @Mock
    private ForecastEvaluationWriter writer;

    @Mock
    private ArrivalObservationQuery arrivalObservationQuery;

    @Captor
    private ArgumentCaptor<List<ForecastEvaluation>> settlements;

    private EvaluateForecastsService job;

    @BeforeEach
    void 회수_배치를_멈춘_시계로_세운다() {
        job =
            new EvaluateForecastsService(work, new EvaluationWorkPageReader(work),
                new EvaluationRouteCommitter(work, quality, writer,
                    new EvaluationQualityRecheck(work, arrivalObservationQuery, properties().arrivalLimit())),
                arrivalObservationQuery, quality, properties(), Clock.fixed(SETTLED_AT, ZoneOffset.UTC), 30000, 3,
                new RecentArrivalObservationCache(false, 30000, Duration.ofMinutes(30)));
    }

    @Test
    void 대상_정류장을_지난_관측이_들어오면_예보_행이_닫힌다() {
        // given
        givenPendingOn(ROUTE_VERSION_3330, pending(100L, 40));
        givenArrivals(passedAt(TARGET_STOP_ORDER, 60, 0));

        // when
        runRound();

        // then
        assertThat(settledLabels()).containsExactly(new Settled(ARRIVAL_OBSERVATION_ID, 0));
    }

    @Test
    void 아직_대상_정류장에_안_닿은_예보는_열어_둔다() {
        // given
        givenPendingOn(ROUTE_VERSION_3330, pending(100L, 40));
        givenArrivals(passedAt(41, 20, 9));

        // when
        runRound();

        // then
        assertThat(settledLabels()).isEmpty();
    }

    @Test
    void 잔여석을_모르는_도착_관측은_좌석_결측으로_닫는다() {
        // given
        givenPendingOn(ROUTE_VERSION_3330, pending(100L, 40));
        givenArrivals(passedAt(TARGET_STOP_ORDER, 60, null));

        // when
        runRound();

        // then
        assertThat(settledLabels()).containsExactly(new SeatMissing(ARRIVAL_OBSERVATION_ID));
    }

    @Test
    void 한_도착_관측이_지평이_다른_예보_여럿을_한꺼번에_닫는다() {
        // given
        givenPendingOn(ROUTE_VERSION_3330, pending(100L, 40), forecastMadeAt(101L, 42, 60));
        givenArrivals(passedAt(42, 60, 9), passedAt(TARGET_STOP_ORDER, 120, 0));

        // when
        runRound();

        // then
        assertThat(settledLabels()).containsExactly(
            new Settled(ARRIVAL_OBSERVATION_ID, 0),
            new Settled(ARRIVAL_OBSERVATION_ID, 0));
    }

    @Test
    void 같은_차량의_예보_여럿은_뒤_관측을_한_번만_읽는다() {
        // given
        givenPendingOn(ROUTE_VERSION_3330, pending(100L, 40), forecastMadeAt(101L, 42, 60));
        givenArrivals(passedAt(42, 60, 9), passedAt(TARGET_STOP_ORDER, 120, 0));

        // when
        runRound();

        // then
        verify(arrivalObservationQuery, times(1)).findAfter(anyLong(), anyString(), any(), anyInt());
    }

    @Test
    void 차량_아이디가_없는_예보는_뒤_관측을_안_읽는다() {
        // given
        givenPendingOn(ROUTE_VERSION_3330, new PendingForecast(
            100L, TARGET_STOP_ORDER, ROUTE_VERSION_3330, null, 4, OBSERVED_AT, OBSERVED_AT));

        // when
        runRound();

        // then
        verify(arrivalObservationQuery, never()).findAfter(anyLong(), anyString(), any(), anyInt());
    }

    @Test
    void 회수_시각은_주입받은_시계에서_온다() {
        // given
        givenPendingOn(ROUTE_VERSION_3330, pending(100L, 40));
        givenArrivals(passedAt(TARGET_STOP_ORDER, 60, 0));

        // when
        runRound();

        // then
        assertThat(captured().getFirst().scoredAt()).isEqualTo(SETTLED_AT);
    }

    @Test
    void 예보를_계산하기_전에_이미_있던_도착_관측은_라벨로_안_쓴다() {
        // given
        givenPendingOn(ROUTE_VERSION_3330, forecastMadeAt(100L, 40, 90));
        givenArrivals(passedAt(TARGET_STOP_ORDER, 30, 0));

        // when
        runRound();

        // then
        assertThat(settledLabels()).isEmpty();
    }

    @Test
    void 도착_후보를_읽는_하한은_예보를_계산한_시각이다() {
        // given
        givenPendingOn(ROUTE_VERSION_3330, forecastMadeAt(100L, 40, 90));
        givenArrivals(passedAt(TARGET_STOP_ORDER, 120, 0));

        // when
        runRound();

        // then
        verify(arrivalObservationQuery)
            .findAfter(anyLong(), anyString(), eq(OBSERVED_AT.plusSeconds(90)), anyInt());
    }

    @Test
    void 유효_기간이_닫힌_판본의_예보도_회수_대상이다() {
        // given
        final long retiredRouteVersion = 9L;
        givenPendingOn(retiredRouteVersion);

        // when
        runRound();

        // then
        verify(work).lastObservationId(retiredRouteVersion);
    }

    @Test
    void 한_회차는_회수된_예보만큼_안_닫힌_행을_줄인다() {
        // given
        givenPendingOn(ROUTE_VERSION_3330, pending(100L, 40), pending(101L, 41), pending(102L, 42));
        givenArrivals(passedAt(TARGET_STOP_ORDER, 60, 0));

        // when
        runRound();

        // then
        assertThat(captured()).hasSize(3);
    }

    @Test
    void 같은_관측의_일부_예보만_완료되면_나머지는_평가를_기다린다() {
        // given
        final long observationId = 100L;
        final int passedStopOrder = TARGET_STOP_ORDER - 1;
        final int laterTarget = TARGET_STOP_ORDER + 1;
        PendingForecast reached = pending(observationId, passedStopOrder);
        PendingForecast waiting = new PendingForecast(
            observationId, laterTarget, ROUTE_VERSION_3330, VEHICLE_ID,
            laterTarget - passedStopOrder, OBSERVED_AT, OBSERVED_AT);
        givenPendingOn(ROUTE_VERSION_3330, reached, waiting);
        givenArrivals(passedAt(TARGET_STOP_ORDER, 60, 0));

        // when
        runRound();

        // then
        assertThat(captured()).singleElement().satisfies(completed -> {
            assertThat(completed.vehicleObservationId()).isEqualTo(observationId);
            assertThat(completed.targetStopOrder()).isEqualTo(TARGET_STOP_ORDER);
            assertThat(completed.state().name()).isEqualTo("SETTLED");
            assertThat(completed.result().arrivalObservationId()).isEqualTo(ARRIVAL_OBSERVATION_ID);
            assertThat(completed.result().seatsOnArrival()).isZero();
            assertThat(completed.scoredAt()).isEqualTo(SETTLED_AT);
        });
    }

    @Test
    void 새_관측이_없으면_메모리의_대기를_유지하고_상세는_다시_읽지_않는다() {
        // given
        givenPendingOn(ROUTE_VERSION_3330, pending(100L, 40));
        givenArrivals();
        final int rounds = 3;

        // when
        for (int round = 0; round < rounds; round++) {
            runRound();
        }

        // then
        verify(writer, times(rounds)).complete(settlements.capture());
        assertThat(settlements.getAllValues()).allSatisfy(
            completed -> assertThat(completed).isEmpty());
        verify(work, times(rounds)).routes();
        verify(work).restore(eq(ROUTE_VERSION_3330), any());
        verify(arrivalObservationQuery, times(rounds)).findAfter(
            ROUTE_VERSION_3330, VEHICLE_ID, OBSERVED_AT, properties().arrivalLimit());
    }

    private void givenPendingOn(
        final long routeVersionId,
        PendingForecast... forecasts
    ) {
        List<Long> ids = Arrays.stream(forecasts)
            .map(PendingForecast::vehicleObservationId)
            .distinct()
            .sorted()
            .toList();
        when(work.routes()).thenReturn(List.of(routeVersionId));
        when(work.lastObservationId(routeVersionId)).thenReturn(lastIdOf(ids));
        if (ids.isEmpty()) {
            return;
        }
        when(work.revisions(eq(routeVersionId), any())).thenReturn(ids.stream()
                .collect(Collectors.toMap(id -> id, id -> 1L)));
        when(work.page(eq(routeVersionId), anyLong(), anyLong(), anyInt())).thenAnswer(call -> {
            final long after = call.getArgument(1);
            return ids.stream()
                .filter(id -> id > after)
                .toList();
        });
        when(work.restore(eq(routeVersionId), any())).thenReturn(Arrays.stream(forecasts)
                .map(f -> new Restored(new Key(f.vehicleObservationId(), f.targetStopOrder()),
                    Optional.of(new PendingForecast(f.vehicleObservationId(), f.targetStopOrder(), routeVersionId,
                        f.vehicleId(), f.stopsToTarget(), f.observedAt(), f.generatedAt(), f.qualityDirection()))))
                .toList());
    }

    private void runRound() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            job.settleArrivalLabels();
            TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private void givenArrivals(ArrivalCandidate... candidates) {
        when(arrivalObservationQuery.findAfter(anyLong(), anyString(), any(), anyInt()))
            .thenReturn(List.of(candidates));
    }

    private List<ArrivalLabel> settledLabels() {
        return captured().stream()
            .<ArrivalLabel>map(evaluation -> switch (evaluation.state()) {
                case SETTLED -> new Settled(evaluation.result()
                    .arrivalObservationId(), evaluation.result()
                    .seatsOnArrival());
                case SEAT_MISSING -> new SeatMissing(evaluation.result().arrivalObservationId());
                case SKIPPED -> new Skipped();
                case LOST -> new Lost();
                case QUALITY_EXCLUDED -> throw new AssertionError("품질 제외는 도착 판정이 아니라 저장소에서 처리한다");
                case PENDING -> throw new AssertionError("평가 대기 상태를 저장하면 안 된다");
            })
            .toList();
    }

    private List<ForecastEvaluation> captured() {
        verify(writer).complete(settlements.capture());
        return settlements.getValue();
    }

    private PendingForecast pending(
        final long vehicleObservationId,
        final int passedStopOrder
    ) {
        return forecastMadeAt(vehicleObservationId, passedStopOrder, 0);
    }

    private PendingForecast forecastMadeAt(
        final long vehicleObservationId,
        final int passedStopOrder,
        final int secondsAfterObserved
    ) {
        return new PendingForecast(
            vehicleObservationId,
            TARGET_STOP_ORDER,
            ROUTE_VERSION_3330,
            VEHICLE_ID,
            TARGET_STOP_ORDER - passedStopOrder,
            OBSERVED_AT.plusSeconds(secondsAfterObserved),
            OBSERVED_AT.plusSeconds(secondsAfterObserved));
    }

    private ArrivalCandidate passedAt(
        final int passedStopOrder,
        final int secondsAfterObserved,
        Integer remainingSeats
    ) {
        return new ArrivalCandidate(
            ARRIVAL_OBSERVATION_ID,
            new ObservedVehicle(
                VEHICLE_ID,
                ROUTE_VERSION_3330,
                passedStopOrder,
                OBSERVED_AT.plusSeconds(secondsAfterObserved),
                remainingSeats,
                CROWD_LEVEL_UNKNOWN));
    }

    private ForecastPolicy properties() {
        return new ForecastPolicy(
            Duration.ofMinutes(5), 20, 3000, 400);
    }

    private static long lastIdOf(List<Long> ids) {
        if (ids.isEmpty()) {
            return 0L;
        }
        return ids.getLast();
    }
}
