package com.gustler.backend.processor;

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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ArrivalLabelJobTest {

    /** 혼잡도를 안 준 관측. 라벨 회수는 혼잡도를 안 본다. */
    private static final Integer CROWD_LEVEL_UNKNOWN = null;

    private static final long ROUTE_VERSION_3330 = 1L;
    private static final String VEHICLE_ID = "204000206";
    private static final Instant OBSERVED_AT = Instant.parse("2026-08-25T08:30:00Z");
    private static final Instant SETTLED_AT = OBSERVED_AT.plusSeconds(600);
    private static final int TARGET_STOP_ORDER = 44;
    private static final long ARRIVAL_OBSERVATION_ID = 7700L;

    @Mock
    private SeatForecastRepository seatForecastRepository;

    @Mock
    private ArrivalObservationRepository arrivalObservationRepository;

    @Captor
    private ArgumentCaptor<List<ForecastSettlement>> settlements;

    private ArrivalLabelJob job;

    @BeforeEach
    void 회수_배치를_멈춘_시계로_세운다() {
        job = new ArrivalLabelJob(
            seatForecastRepository,
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
        verify(seatForecastRepository).findPending(eq(retiredRouteVersion), anyInt());
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
        when(seatForecastRepository.findRouteVersionIdsWithPendingForecasts())
            .thenReturn(List.of(routeVersionId));
        when(seatForecastRepository.findPending(anyLong(), anyInt())).thenReturn(List.of(forecasts));
    }

    private void givenArrivals(
        ArrivalCandidate... candidates
    ) {
        when(arrivalObservationRepository.findAfter(anyLong(), anyString(), any(), anyInt()))
            .thenReturn(List.of(candidates));
    }

    private List<ArrivalLabel> settledLabels() {
        return captured().stream().map(ForecastSettlement::label).toList();
    }

    private List<ForecastSettlement> captured() {
        verify(seatForecastRepository).settle(settlements.capture());
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

    private ForecastProperties properties() {
        return new ForecastProperties(
            true, Duration.ofSeconds(10), Duration.ofSeconds(60), Duration.ofHours(6), 20, 3000, 400);
    }
}
