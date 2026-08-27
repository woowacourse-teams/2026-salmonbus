package com.gustler.backend.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.collector.RemainingSeats.Known;
import com.gustler.backend.collector.RemainingSeats.Unknown;
import com.gustler.backend.collector.dto.BusLocationResponse.BusLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VehicleObservationTest {

    private static final String VEHICLE_204000206 = "204000206";
    private static final String PLATE_NUMBER = "경기70아0001";
    private static final String ROUTE_3330 = "204000057";
    private static final String STOP_205000217 = "205000217";
    private static final int STOP_ORDER_6 = 6;
    private static final int ROUTE_TYPE_11 = 11;
    private static final int TAGLESS_1 = 1;

    private static final int NORMAL_BUS = 0;

    private static final int RUNNING_STATE_DEPARTED = 2;

    private static final int SEATS_43 = 43;
    private static final int CROWD_LEVEL_3 = 3;

    @Test
    void 잔여석이_있는_차량은_그_수를_그대로_쓴다() {
        // given
        final BusLocation bus = busWithSeats(SEATS_43);

        // when
        final VehicleObservation actual = VehicleObservation.from(bus);

        // then
        assertThat(actual.remainingSeats()).isEqualTo(new Known(SEATS_43));
    }

    @Test
    void 잔여석이_0인_차량은_잔여석을_아는_차량이다() {
        // given
        final BusLocation bus = busWithSeats(0);

        // when
        final VehicleObservation actual = VehicleObservation.from(bus);

        // then
        assertThat(actual.remainingSeats()).isEqualTo(new Known(0));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, -2, -99})
    void 잔여석이_음수인_차량은_모른다고_알려준_것으로_판단한다(
        final int remainingSeatCount
    ) {
        // given
        final BusLocation bus = busWithSeats(remainingSeatCount);

        // when
        final VehicleObservation actual = VehicleObservation.from(bus);

        // then
        assertThat(actual.remainingSeats())
            .isEqualTo(new Unknown(SeatUnknownReason.REPORTED_UNKNOWN));
    }

    @Test
    void 잔여석_항목이_없는_차량은_알려주지_않은_것으로_판단한다() {
        // given
        final BusLocation bus = busWithSeats(null);

        // when
        final VehicleObservation actual = VehicleObservation.from(bus);

        // then
        assertThat(actual.remainingSeats())
            .isEqualTo(new Unknown(SeatUnknownReason.NOT_REPORTED));
    }

    @Test
    void 아는_잔여석은_음수일_수_없다() {
        // when & then
        assertThatThrownBy(() -> new Known(-1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 잔여석을_모르는_경우에도_사유는_반드시_있다() {
        // when & then
        assertThatThrownBy(() -> new Unknown(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void 관측에는_잔여석이_아는_것이든_모르는_것이든_들어간다() {
        // when & then
        assertThatThrownBy(() -> new VehicleObservation(
            VEHICLE_204000206,
            PLATE_NUMBER,
            STOP_ORDER_6,
            STOP_205000217,
            RUNNING_STATE_DEPARTED,
            null,
            CROWD_LEVEL_3,
            NORMAL_BUS,
            ROUTE_TYPE_11,
            TAGLESS_1))
            .isInstanceOf(NullPointerException.class);
    }

    private static BusLocation busWithSeats(
        final Integer remainingSeatCount
    ) {
        return bus(STOP_ORDER_6, RUNNING_STATE_DEPARTED, remainingSeatCount, CROWD_LEVEL_3, NORMAL_BUS);
    }

    private static BusLocation bus(
        final Integer stopOrder,
        final Integer runningState,
        final Integer remainingSeatCount,
        final Integer crowdLevel,
        final Integer vehicleType
    ) {
        return new BusLocation(
            PLATE_NUMBER,
            VEHICLE_204000206,
            vehicleType,
            ROUTE_3330,
            ROUTE_TYPE_11,
            STOP_205000217,
            stopOrder,
            runningState,
            remainingSeatCount,
            crowdLevel,
            TAGLESS_1);
    }
}
