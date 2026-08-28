package com.gustler.backend.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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

    private static final long ROUTE_VERSION_3330 = 1L;
    private static final String VEHICLE_ID = "204000206";
    private static final Instant OBSERVED_AT = Instant.parse("2026-08-25T08:30:00Z");
    private static final Instant SETTLED_AT = Instant.parse("2026-08-25T08:40:00Z");
    private static final int TARGET_STOP_ORDER = 44;
    private static final long ARRIVAL_OBSERVATION_ID = 7700L;

    @Mock
    private RouteVersionRepository routeVersionRepository;

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
            routeVersionRepository,
            seatForecastRepository,
            arrivalObservationRepository,
            properties(),
            Clock.fixed(SETTLED_AT, ZoneOffset.UTC));
        when(routeVersionRepository.findActiveVersionIds()).thenReturn(List.of(ROUTE_VERSION_3330));
    }

    @Test
    void 대상_정류장을_지난_관측이_들어오면_예보_행이_닫힌다() {
        // given
        givenPending(pending(100L, 40));
        givenArrivals(passedAt(TARGET_STOP_ORDER, 60, 0));

        // when
        job.settleArrivalLabels();

        // then
        assertThat(settledLabels()).containsExactly(new ArrivalLabel.Settled(ARRIVAL_OBSERVATION_ID, 0));
    }

    @Test
    void 아직_대상_정류장에_안_닿은_예보는_열어_둔다() {
        // given
        givenPending(pending(100L, 40));
        givenArrivals(passedAt(41, 20, 9));

        // when
        job.settleArrivalLabels();

        // then
        assertThat(settledLabels()).isEmpty();
    }

    @Test
    void 잔여석을_모르는_도착_관측은_좌석_결측으로_닫는다() {
        // given
        givenPending(pending(100L, 40));
        givenArrivals(passedAt(TARGET_STOP_ORDER, 60, null));

        // when
        job.settleArrivalLabels();

        // then
        assertThat(settledLabels()).containsExactly(new ArrivalLabel.SeatMissing(ARRIVAL_OBSERVATION_ID));
    }

    @Test
    void 한_도착_관측이_지평이_다른_예보_여럿을_한꺼번에_닫는다() {
        // given
        givenPending(pending(100L, 40), pendingObservedLater(101L, 42, 60));
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
        givenPending(pending(100L, 40), pendingObservedLater(101L, 42, 60));
        givenArrivals(passedAt(42, 60, 9), passedAt(TARGET_STOP_ORDER, 120, 0));

        // when
        job.settleArrivalLabels();

        // then
        verify(arrivalObservationRepository, times(1)).findAfter(anyLong(), anyString(), any(), anyInt());
    }

    @Test
    void 차량_아이디가_없는_예보는_뒤_관측을_안_읽는다() {
        // given
        givenPending(new PendingForecast(100L, TARGET_STOP_ORDER, ROUTE_VERSION_3330, null, 4, OBSERVED_AT));

        // when
        job.settleArrivalLabels();

        // then
        verify(arrivalObservationRepository, never()).findAfter(anyLong(), anyString(), any(), anyInt());
    }

    @Test
    void 회수_시각은_주입받은_시계에서_온다() {
        // given
        givenPending(pending(100L, 40));
        givenArrivals(passedAt(TARGET_STOP_ORDER, 60, 0));

        // when
        job.settleArrivalLabels();

        // then
        assertThat(captured().getFirst().scoredAt()).isEqualTo(SETTLED_AT);
    }

    private void givenPending(
        PendingForecast... forecasts
    ) {
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
        return new PendingForecast(
            vehicleObservationId,
            TARGET_STOP_ORDER,
            ROUTE_VERSION_3330,
            VEHICLE_ID,
            TARGET_STOP_ORDER - passedStopOrder,
            OBSERVED_AT);
    }

    private PendingForecast pendingObservedLater(
        final long vehicleObservationId,
        final int passedStopOrder,
        final int secondsLater
    ) {
        return new PendingForecast(
            vehicleObservationId,
            TARGET_STOP_ORDER,
            ROUTE_VERSION_3330,
            VEHICLE_ID,
            TARGET_STOP_ORDER - passedStopOrder,
            OBSERVED_AT.plusSeconds(secondsLater));
    }

    private ArrivalCandidate passedAt(
        final int passedStopOrder,
        final int secondsAfterForecast,
        Integer remainingSeats
    ) {
        return new ArrivalCandidate(
            ARRIVAL_OBSERVATION_ID,
            new ObservedVehicle(
                VEHICLE_ID,
                ROUTE_VERSION_3330,
                passedStopOrder,
                OBSERVED_AT.plusSeconds(secondsAfterForecast),
                remainingSeats));
    }

    private ForecastProperties properties() {
        return new ForecastProperties(
            true, Duration.ofSeconds(10), Duration.ofSeconds(60), 20, 500, 200);
    }
}
