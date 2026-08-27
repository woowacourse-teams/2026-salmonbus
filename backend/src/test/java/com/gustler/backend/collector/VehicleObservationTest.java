package com.gustler.backend.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.collector.RemainingSeats.Known;
import com.gustler.backend.collector.RemainingSeats.Unknown;
import com.gustler.backend.collector.dto.BusLocationResponse.BusLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class VehicleObservationTest {

    private static final String VEHICLE_204000206 = "204000206";
    private static final String PLATE_NUMBER = "경기70아0001";
    private static final String ROUTE_3330 = "204000057";
    private static final String STOP_205000217 = "205000217";
    private static final int STOP_ORDER_6 = 6;
    private static final int FIRST_STOP_ORDER = 1;
    private static final int ROUTE_TYPE_11 = 11;
    private static final int TAGLESS_1 = 1;

    private static final int NORMAL_BUS = 0;
    private static final int DOUBLE_DECKER_BUS = 2;

    private static final int RUNNING_STATE_ARRIVED = 1;
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

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    void 혼잡도가_1에서_4까지면_그대로_쓴다(
        final int crowdLevel
    ) {
        // given
        final BusLocation bus = busWithCrowdLevel(crowdLevel);

        // when
        final VehicleObservation actual = VehicleObservation.from(bus);

        // then
        assertThat(actual.crowdLevel()).isEqualTo(crowdLevel);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, 5, -1})
    void 혼잡도가_1에서_4가_아니면_모르는_것으로_판단한다(
        final Integer crowdLevel
    ) {
        // given
        final BusLocation bus = busWithCrowdLevel(crowdLevel);

        // when
        final VehicleObservation actual = VehicleObservation.from(bus);

        // then
        assertThat(actual.crowdLevel()).isNull();
    }

    @Test
    void 잔여석을_몰라도_혼잡도_3은_그대로_쓴다() {
        // given
        final BusLocation bus = bus(STOP_ORDER_6, RUNNING_STATE_DEPARTED, -1, CROWD_LEVEL_3, NORMAL_BUS);

        // when
        final VehicleObservation actual = VehicleObservation.from(bus);

        // then
        assertThat(actual.remainingSeats()).isInstanceOf(Unknown.class);
        assertThat(actual.crowdLevel()).isEqualTo(CROWD_LEVEL_3);
    }

    @Test
    void 혼잡도를_몰라도_잔여석_43석은_그대로_쓴다() {
        // given
        final BusLocation bus = bus(STOP_ORDER_6, RUNNING_STATE_DEPARTED, SEATS_43, 0, NORMAL_BUS);

        // when
        final VehicleObservation actual = VehicleObservation.from(bus);

        // then
        assertThat(actual.crowdLevel()).isNull();
        assertThat(actual.remainingSeats()).isEqualTo(new Known(SEATS_43));
    }

    @Test
    void 이층버스의_차량_유형은_2이고_잔여석은_43석_그대로다() {
        // given
        final BusLocation bus = bus(STOP_ORDER_6, RUNNING_STATE_DEPARTED, SEATS_43, CROWD_LEVEL_3, DOUBLE_DECKER_BUS);

        // when
        final VehicleObservation actual = VehicleObservation.from(bus);

        // then
        assertThat(actual.vehicleType()).isEqualTo(DOUBLE_DECKER_BUS);
        assertThat(actual.remainingSeats()).isEqualTo(new Known(SEATS_43));
    }

    @Test
    void 정류소_순번은_Open_API가_준_순번을_그대로_쓴다() {
        // given
        final BusLocation bus = busAt(STOP_ORDER_6, RUNNING_STATE_DEPARTED);

        // when
        final VehicleObservation actual = VehicleObservation.from(bus);

        // then
        assertThat(actual.stopOrder()).isEqualTo(STOP_ORDER_6);
    }

    @Test
    void 첫_정류소에_도착한_버스의_순번은_1이다() {
        // given
        final BusLocation bus = busAt(FIRST_STOP_ORDER, RUNNING_STATE_ARRIVED);

        // when
        final VehicleObservation actual = VehicleObservation.from(bus);

        // then
        assertThat(actual.stopOrder()).isEqualTo(FIRST_STOP_ORDER);
    }

    @Test
    void 차량_아이디는_Open_API가_준_값을_문자열_그대로_쓴다() {
        // given
        final BusLocation bus = busAt(STOP_ORDER_6, RUNNING_STATE_DEPARTED);

        // when
        final VehicleObservation actual = VehicleObservation.from(bus);

        // then
        assertThat(actual.vehicleId()).isEqualTo(VEHICLE_204000206);
    }

    @Test
    void 번호판도_관측에_담는다() {
        // given
        final BusLocation bus = busAt(STOP_ORDER_6, RUNNING_STATE_DEPARTED);

        // when
        final VehicleObservation actual = VehicleObservation.from(bus);

        // then
        assertThat(actual.plateNumber()).isEqualTo(PLATE_NUMBER);
    }

    @Test
    void Open_API가_준_차량_한_대를_관측_한_건으로_만든다() {
        // given
        final BusLocation bus = bus(STOP_ORDER_6, RUNNING_STATE_DEPARTED, SEATS_43, CROWD_LEVEL_3, DOUBLE_DECKER_BUS);

        // when
        final VehicleObservation actual = VehicleObservation.from(bus);

        // then
        assertThat(actual).isEqualTo(new VehicleObservation(
            VEHICLE_204000206,
            PLATE_NUMBER,
            STOP_ORDER_6,
            STOP_205000217,
            RUNNING_STATE_DEPARTED,
            new Known(SEATS_43),
            CROWD_LEVEL_3,
            DOUBLE_DECKER_BUS,
            ROUTE_TYPE_11,
            TAGLESS_1));
    }

    private static BusLocation busAt(
        final int stopOrder,
        final int runningState
    ) {
        return bus(stopOrder, runningState, SEATS_43, CROWD_LEVEL_3, NORMAL_BUS);
    }

    private static BusLocation busWithSeats(
        final Integer remainingSeatCount
    ) {
        return bus(STOP_ORDER_6, RUNNING_STATE_DEPARTED, remainingSeatCount, CROWD_LEVEL_3, NORMAL_BUS);
    }

    private static BusLocation busWithCrowdLevel(
        final Integer crowdLevel
    ) {
        return bus(STOP_ORDER_6, RUNNING_STATE_DEPARTED, SEATS_43, crowdLevel, NORMAL_BUS);
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
