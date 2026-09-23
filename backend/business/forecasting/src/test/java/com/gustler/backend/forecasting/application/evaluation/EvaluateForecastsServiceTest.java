package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.forecasting.domain.evaluation.ArrivalCandidate;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabel;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalObservationRepository;
import com.gustler.backend.forecasting.domain.evaluation.ForecastEvaluation;
import com.gustler.backend.forecasting.domain.evaluation.PendingForecast;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationRoute;
import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.domain.model.ObservedVehicle;
import com.gustler.backend.forecasting.domain.evaluation.ForecastEvaluationRepository;
import com.gustler.backend.forecasting.api.ForecastPolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EvaluateForecastsServiceTest {

    /** 혼잡도를 안 준 관측. 라벨 회수는 혼잡도를 안 본다. */
    private static final Integer CROWD_LEVEL_UNKNOWN = null;

    private static final long ROUTE_3330 = 10L;
    private static final long ROUTE_VERSION_3330 = 1L;
    private static final String VEHICLE_ID = "204000206";
    private static final Instant OBSERVED_AT = Instant.parse("2026-08-25T08:30:00Z");
    private static final Instant SETTLED_AT = OBSERVED_AT.plusSeconds(600);
    private static final int TARGET_STOP_ORDER = 44;
    private static final long ARRIVAL_OBSERVATION_ID = 7700L;

    @Mock
    private ForecastEvaluationRepository evaluationRepository;

    @Mock
    private ForecastEvaluationWriter writer;

    @Mock
    private RouteDataQualityAccess quality;

    @Mock
    private ArrivalObservationRepository arrivalObservationRepository;

    @Captor
    private ArgumentCaptor<List<ForecastEvaluation>> settlements;

    private EvaluateForecastsService job;

    @BeforeEach
    void 회수_배치를_멈춘_시계로_세운다() {
        job = new EvaluateForecastsService(
            evaluationRepository,
            writer,
            quality,
            arrivalObservationRepository,
            properties(),
            Clock.fixed(SETTLED_AT, ZoneOffset.UTC));
    }

    @Test
    void 대상_정류장을_지난_관측이_들어오면_예보_행이_닫힌다() {
        // given
        givenPendingOn(ROUTE_VERSION_3330, pending(100L, 40));
        givenArrivals(passedAt(TARGET_STOP_ORDER, 60, 0));

        // when
        job.settleArrivalLabels();

        // then
        assertThat(settledLabels()).containsExactly(new ArrivalLabel.Settled(ARRIVAL_OBSERVATION_ID, 0));
    }

    @Test
    void 품질을_잠근_뒤_후보를_조회하고_확정_작업을_호출한다() {
        givenPendingOn(ROUTE_VERSION_3330, pending(100L, 40));
        givenArrivals(passedAt(TARGET_STOP_ORDER, 60, 0));

        job.settleArrivalLabels();

        InOrder order = inOrder(quality, evaluationRepository, arrivalObservationRepository, writer);
        order.verify(quality).lockByRoute(ROUTE_3330);
        order.verify(evaluationRepository).findPending(eq(ROUTE_VERSION_3330), anyInt());
        order.verify(arrivalObservationRepository).findAfter(anyLong(), anyString(), any(), anyInt());
        order.verify(writer).complete(any());
    }

    @Test
    void 여러_노선은_노선_ID_순서로_잠근_뒤_평가한다() {
        when(evaluationRepository.findRoutesWithPendingForecasts()).thenReturn(List.of(
            new EvaluationRoute(20L, 4L), new EvaluationRoute(10L, 3L), new EvaluationRoute(10L, 2L)));

        job.settleArrivalLabels();

        InOrder order = inOrder(quality, evaluationRepository);
        order.verify(quality).lockByRoute(10L);
        order.verify(quality).lockByRoute(20L);
        order.verify(evaluationRepository).findPending(eq(4L), anyInt());
        order.verify(evaluationRepository).findPending(eq(3L), anyInt());
        order.verify(evaluationRepository).findPending(eq(2L), anyInt());
    }

    @Test
    void 아직_대상_정류장에_안_닿은_예보는_열어_둔다() {
        // given
        givenPendingOn(ROUTE_VERSION_3330, pending(100L, 40));
        givenArrivals(passedAt(41, 20, 9));

        // when
        job.settleArrivalLabels();

        // then
        assertThat(settledLabels()).isEmpty();
    }

    @Test
    void 잔여석을_모르는_도착_관측은_좌석_결측으로_닫는다() {
        // given
        givenPendingOn(ROUTE_VERSION_3330, pending(100L, 40));
        givenArrivals(passedAt(TARGET_STOP_ORDER, 60, null));

        // when
        job.settleArrivalLabels();

        // then
        assertThat(settledLabels()).containsExactly(new ArrivalLabel.SeatMissing(ARRIVAL_OBSERVATION_ID));
    }

    @Test
    void 한_도착_관측이_지평이_다른_예보_여럿을_한꺼번에_닫는다() {
        // given
        givenPendingOn(ROUTE_VERSION_3330, pending(100L, 40), forecastMadeAt(101L, 42, 60));
        givenArrivals(passedAt(42, 60, 9), passedAt(TARGET_STOP_ORDER, 120, 0));

        // when
        job.settleArrivalLabels();

        // then
        assertThat(settledLabels()).containsExactly(
            new ArrivalLabel.Settled(ARRIVAL_OBSERVATION_ID, 0),
            new ArrivalLabel.Settled(ARRIVAL_OBSERVATION_ID, 0));
    }

    @Test
    void 같은_차량의_예보_여럿은_뒤_관측을_한_번만_읽는다() {
        // given
        givenPendingOn(ROUTE_VERSION_3330, pending(100L, 40), forecastMadeAt(101L, 42, 60));
        givenArrivals(passedAt(42, 60, 9), passedAt(TARGET_STOP_ORDER, 120, 0));

        // when
        job.settleArrivalLabels();

        // then
        verify(arrivalObservationRepository, times(1)).findAfter(anyLong(), anyString(), any(), anyInt());
    }

    @Test
    void 차량_아이디가_없는_예보는_뒤_관측을_안_읽는다() {
        // given
        givenPendingOn(ROUTE_VERSION_3330, new PendingForecast(
            100L, TARGET_STOP_ORDER, ROUTE_VERSION_3330, null, 4, OBSERVED_AT, OBSERVED_AT));

        // when
        job.settleArrivalLabels();

        // then
        verify(arrivalObservationRepository, never()).findAfter(anyLong(), anyString(), any(), anyInt());
    }

    @Test
    void 회수_시각은_주입받은_시계에서_온다() {
        // given
        givenPendingOn(ROUTE_VERSION_3330, pending(100L, 40));
        givenArrivals(passedAt(TARGET_STOP_ORDER, 60, 0));

        // when
        job.settleArrivalLabels();

        // then
        assertThat(captured().getFirst().scoredAt()).isEqualTo(SETTLED_AT);
    }

    @Test
    void 예보를_계산하기_전에_이미_있던_도착_관측은_라벨로_안_쓴다() {
        // given
        givenPendingOn(ROUTE_VERSION_3330, forecastMadeAt(100L, 40, 90));
        givenArrivals(passedAt(TARGET_STOP_ORDER, 30, 0));

        // when
        job.settleArrivalLabels();

        // then
        assertThat(settledLabels()).isEmpty();
    }

    @Test
    void 도착_후보를_읽는_하한은_예보를_계산한_시각이다() {
        // given
        givenPendingOn(ROUTE_VERSION_3330, forecastMadeAt(100L, 40, 90));
        givenArrivals(passedAt(TARGET_STOP_ORDER, 120, 0));

        // when
        job.settleArrivalLabels();

        // then
        verify(arrivalObservationRepository)
            .findAfter(anyLong(), anyString(), eq(OBSERVED_AT.plusSeconds(90)), anyInt());
    }

    @Test
    void 유효_기간이_닫힌_판본의_예보도_회수_대상이다() {
        // given
        final long retiredRouteVersion = 9L;
        givenPendingOn(retiredRouteVersion);

        // when
        job.settleArrivalLabels();

        // then
        verify(evaluationRepository).findPending(eq(retiredRouteVersion), anyInt());
    }

    @Test
    void 한_회차는_회수된_예보만큼_안_닫힌_행을_줄인다() {
        // given
        givenPendingOn(ROUTE_VERSION_3330, pending(100L, 40), pending(101L, 41), pending(102L, 42));
        givenArrivals(passedAt(TARGET_STOP_ORDER, 60, 0));

        // when
        job.settleArrivalLabels();

        // then
        assertThat(captured()).hasSize(3);
    }

    private void givenPendingOn(
        final long routeVersionId,
        PendingForecast... forecasts
    ) {
        when(evaluationRepository.findRoutesWithPendingForecasts())
            .thenReturn(List.of(new EvaluationRoute(ROUTE_3330, routeVersionId)));
        when(evaluationRepository.findPending(anyLong(), anyInt())).thenReturn(List.of(forecasts));
    }

    private void givenArrivals(
        ArrivalCandidate... candidates
    ) {
        when(arrivalObservationRepository.findAfter(anyLong(), anyString(), any(), anyInt()))
            .thenReturn(List.of(candidates));
    }

    private List<ArrivalLabel> settledLabels() {
        return captured().stream().<ArrivalLabel>map(evaluation -> switch (evaluation.state()) {
            case SETTLED -> new ArrivalLabel.Settled(evaluation.result().arrivalObservationId(), evaluation.result().seatsOnArrival());
            case SEAT_MISSING -> new ArrivalLabel.SeatMissing(evaluation.result().arrivalObservationId());
            case SKIPPED -> new ArrivalLabel.Skipped();
            case LOST -> new ArrivalLabel.Lost();
            case PENDING -> throw new AssertionError("평가 대기 상태를 저장하면 안 된다");
        }).toList();
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
}
