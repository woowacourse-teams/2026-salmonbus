package com.gustler.backend.api.vehicle.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.route.RouteNotFoundException;
import com.gustler.backend.api.vehicle.domain.ObservedVehicle;
import com.gustler.backend.api.vehicle.domain.VehicleDirection;
import com.gustler.backend.api.vehicle.domain.VehicleObservationState;
import com.gustler.backend.api.vehicle.domain.VehiclePhase;
import com.gustler.backend.api.vehicle.domain.VehiclePollOutcome;
import com.gustler.backend.api.vehicle.domain.VehicleSeat;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LiveVehicleQueryServiceTest {

    private static final RouteId ROUTE_ID = new RouteId("204000057");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Clock CLOCK = clockAt("2026-08-27T12:00:00+09:00");
    private static final OffsetDateTime RECENT = OffsetDateTime.parse(
        "2026-08-27T11:58:00+09:00"
    );
    private static final ObservedVehicle VEHICLE = new ObservedVehicle(
        "204000206",
        VehicleDirection.UP,
        5,
        "205000217",
        "범계역",
        VehiclePhase.DEPARTED,
        new VehicleSeat.Exact(0)
    );

    @Mock
    private VehicleQueryRepository vehicleQueryRepository;

    private LiveVehicleQueryService service;

    @BeforeEach
    void setUp() {
        service = serviceAt(CLOCK);
    }

    @Test
    void 최신_정상_poll에_차량이_있으면_차량_현황을_반환한다() {
        given(vehicleQueryRepository.findLatestSnapshot(ROUTE_ID))
            .willReturn(Optional.of(snapshot(
                1L,
                VehiclePollOutcome.SUCCESS_ROWS,
                RECENT
            )));
        given(vehicleQueryRepository.findVehicles(1L)).willReturn(List.of(VEHICLE));

        LiveVehicleOverview actual = service.getLiveVehicles(ROUTE_ID);

        assertThat(actual.state()).isEqualTo(VehicleObservationState.VEHICLES_PRESENT);
        assertThat(actual.observedAt()).isEqualTo(RECENT);
        assertThat(actual.staleAt()).isEqualTo(RECENT.plusMinutes(5));
        assertThat(actual.vehicles()).containsExactly(VEHICLE);
        assertThat(actual.cacheMaxAge()).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void 최신_정상_poll이_SUCCESS_EMPTY면_차량이_없다는_정상_상태를_반환한다() {
        given(vehicleQueryRepository.findLatestSnapshot(ROUTE_ID))
            .willReturn(Optional.of(snapshot(
                2L,
                VehiclePollOutcome.SUCCESS_EMPTY,
                RECENT
            )));

        LiveVehicleOverview actual = service.getLiveVehicles(ROUTE_ID);

        assertThat(actual.state()).isEqualTo(
            VehicleObservationState.NO_VEHICLES_OBSERVED
        );
        assertThat(actual.vehicles()).isEmpty();
        verify(vehicleQueryRepository, never()).findVehicles(2L);
    }

    @Test
    void 최신_poll이_실패하면_마지막_정상_시각을_주되_차량은_반환하지_않는다() {
        given(vehicleQueryRepository.findLatestSnapshot(ROUTE_ID))
            .willReturn(Optional.of(snapshot(3L, VehiclePollOutcome.UNKNOWN, RECENT)));

        LiveVehicleOverview actual = service.getLiveVehicles(ROUTE_ID);

        assertThat(actual.state()).isEqualTo(VehicleObservationState.UNKNOWN);
        assertThat(actual.observedAt()).isEqualTo(RECENT);
        assertThat(actual.staleAt()).isEqualTo(RECENT.plusMinutes(5));
        assertThat(actual.vehicles()).isEmpty();
        verify(vehicleQueryRepository, never()).findVehicles(3L);
    }

    @Test
    void 최신_정상_스냅샷이_자기_staleAt을_넘겼으면_UNKNOWN으로_강등한다() {
        OffsetDateTime oldObservation = OffsetDateTime.parse(
            "2026-08-27T11:54:59+09:00"
        );
        given(vehicleQueryRepository.findLatestSnapshot(ROUTE_ID))
            .willReturn(Optional.of(snapshot(
                4L,
                VehiclePollOutcome.SUCCESS_ROWS,
                oldObservation
            )));

        LiveVehicleOverview actual = service.getLiveVehicles(ROUTE_ID);

        assertThat(actual.state()).isEqualTo(VehicleObservationState.UNKNOWN);
        assertThat(actual.observedAt()).isEqualTo(oldObservation);
        assertThat(actual.staleAt()).isEqualTo(oldObservation.plusMinutes(5));
        assertThat(actual.vehicles()).isEmpty();
        verify(vehicleQueryRepository, never()).findVehicles(4L);
    }

    @Test
    void 현재_시각이_staleAt과_같으면_아직_차량을_반환한다() {
        OffsetDateTime boundaryObservation = OffsetDateTime.parse(
            "2026-08-27T11:55:00+09:00"
        );
        given(vehicleQueryRepository.findLatestSnapshot(ROUTE_ID))
            .willReturn(Optional.of(snapshot(
                5L,
                VehiclePollOutcome.SUCCESS_ROWS,
                boundaryObservation
            )));
        given(vehicleQueryRepository.findVehicles(5L)).willReturn(List.of(VEHICLE));

        LiveVehicleOverview actual = service.getLiveVehicles(ROUTE_ID);

        assertThat(actual.state()).isEqualTo(VehicleObservationState.VEHICLES_PRESENT);
        assertThat(actual.vehicles()).containsExactly(VEHICLE);
    }

    @Test
    void 정상_수집이_한_번도_없으면_시각이_없는_UNKNOWN을_반환한다() {
        VehicleSnapshot neverObserved = new VehicleSnapshot(
            ROUTE_ID.value(),
            "42",
            null,
            VehiclePollOutcome.UNKNOWN,
            null
        );
        given(vehicleQueryRepository.findLatestSnapshot(ROUTE_ID))
            .willReturn(Optional.of(neverObserved));

        LiveVehicleOverview actual = service.getLiveVehicles(ROUTE_ID);

        assertThat(actual.state()).isEqualTo(VehicleObservationState.UNKNOWN);
        assertThat(actual.observedAt()).isNull();
        assertThat(actual.staleAt()).isNull();
        assertThat(actual.vehicles()).isEmpty();
    }

    @Test
    void 관측이_비혼잡_시간대여도_응답_시각이_혼잡_시간대면_15초를_붙인다() {
        Clock peakClock = clockAt("2026-08-27T07:00:00+09:00");
        OffsetDateTime observedAt = OffsetDateTime.parse("2026-08-27T06:59:30+09:00");
        given(vehicleQueryRepository.findLatestSnapshot(ROUTE_ID))
            .willReturn(Optional.of(snapshot(
                6L,
                VehiclePollOutcome.SUCCESS_ROWS,
                observedAt
            )));
        given(vehicleQueryRepository.findVehicles(6L)).willReturn(List.of(VEHICLE));

        LiveVehicleOverview actual = serviceAt(peakClock).getLiveVehicles(ROUTE_ID);

        assertThat(actual.cacheMaxAge()).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    void 응답_시각이_혼잡_시간대_직전이면_20초를_붙인다() {
        Clock beforePeakClock = clockAt("2026-08-27T06:59:59+09:00");
        OffsetDateTime observedAt = OffsetDateTime.parse("2026-08-27T06:59:30+09:00");
        given(vehicleQueryRepository.findLatestSnapshot(ROUTE_ID))
            .willReturn(Optional.of(snapshot(
                7L,
                VehiclePollOutcome.SUCCESS_ROWS,
                observedAt
            )));
        given(vehicleQueryRepository.findVehicles(7L)).willReturn(List.of(VEHICLE));

        LiveVehicleOverview actual = serviceAt(beforePeakClock).getLiveVehicles(ROUTE_ID);

        assertThat(actual.cacheMaxAge()).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void 관측이_혼잡_시간대여도_응답_시각이_심야면_600초를_붙인다() {
        Clock overnightClock = clockAt("2026-08-27T01:00:00+09:00");
        OffsetDateTime observedAt = OffsetDateTime.parse("2026-08-27T00:59:00+09:00");
        given(vehicleQueryRepository.findLatestSnapshot(ROUTE_ID))
            .willReturn(Optional.of(snapshot(
                8L,
                VehiclePollOutcome.SUCCESS_ROWS,
                observedAt
            )));
        given(vehicleQueryRepository.findVehicles(8L)).willReturn(List.of(VEHICLE));

        LiveVehicleOverview actual = serviceAt(overnightClock).getLiveVehicles(ROUTE_ID);

        assertThat(actual.cacheMaxAge()).isEqualTo(Duration.ofSeconds(600));
    }

    @Test
    void 현재_판본이_있는_노선이_없으면_찾을_수_없음_예외를_던진다() {
        given(vehicleQueryRepository.findLatestSnapshot(ROUTE_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getLiveVehicles(ROUTE_ID))
            .isInstanceOf(RouteNotFoundException.class);
    }

    private LiveVehicleQueryService serviceAt(Clock clock) {
        return new LiveVehicleQueryService(
            vehicleQueryRepository,
            new VehicleFreshnessPolicy(clock),
            new VehicleCachePolicy(),
            clock
        );
    }

    private VehicleSnapshot snapshot(
        Long batchId,
        VehiclePollOutcome outcome,
        OffsetDateTime observedAt
    ) {
        return new VehicleSnapshot(
            ROUTE_ID.value(),
            "42",
            batchId,
            outcome,
            observedAt
        );
    }

    private static Clock clockAt(String isoOffsetDateTime) {
        return Clock.fixed(
            OffsetDateTime.parse(isoOffsetDateTime).toInstant(),
            SEOUL
        );
    }
}
