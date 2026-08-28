package com.gustler.backend.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.collector.dto.BusLocationResponse.BusLocation;
import java.util.List;
import org.junit.jupiter.api.Test;

class CollectedObservationsTest {

    private static final String PLATE_NUMBER = "경기70아0001";
    private static final String ROUTE_3330 = "204000057";
    private static final String STOP_205000217 = "205000217";
    private static final String VEHICLE_204000206 = "204000206";
    private static final String VEHICLE_204003542 = "204003542";
    private static final String VEHICLE_204000139 = "204000139";

    private static final int STOP_SEQUENCE_6 = 6;
    private static final int ROUTE_TYPE_11 = 11;
    private static final int TAGLESS_1 = 1;
    private static final int NORMAL_BUS = 0;
    private static final int SEATS_43 = 43;
    private static final int CROWD_LEVEL_3 = 3;

    private static final int RUNNING_STATE_MOVING = 0;
    private static final int RUNNING_STATE_ARRIVED = 1;
    private static final int RUNNING_STATE_DEPARTED = 2;
    private static final int RUNNING_STATE_NEVER_OBSERVED = 3;

    @Test
    void 상류가_준_차량_수를_그대로_센다() {
        // given
        List<BusLocation> buses = List.of(
            bus(VEHICLE_204000206, RUNNING_STATE_MOVING),
            bus(VEHICLE_204003542, RUNNING_STATE_NEVER_OBSERVED),
            bus(VEHICLE_204000139, RUNNING_STATE_DEPARTED));

        // when
        CollectedObservations actual = CollectedObservations.from(buses);

        // then
        assertThat(actual.providerRows()).isEqualTo(3);
    }

    @Test
    void 뜻을_아는_운행_상태의_차량만_쌓을_대상이_된다() {
        // given
        List<BusLocation> buses = List.of(
            bus(VEHICLE_204000206, RUNNING_STATE_MOVING),
            bus(VEHICLE_204003542, RUNNING_STATE_NEVER_OBSERVED),
            bus(VEHICLE_204000139, RUNNING_STATE_DEPARTED));

        // when
        CollectedObservations actual = CollectedObservations.from(buses);

        // then
        assertThat(actual.storableRows())
            .extracting(row -> row.observation().vehicleId())
            .containsExactly(VEHICLE_204000206, VEHICLE_204000139);
    }

    @Test
    void 뜻을_모르는_운행_상태의_차량_수를_따로_센다() {
        // given
        List<BusLocation> buses = List.of(
            bus(VEHICLE_204000206, RUNNING_STATE_MOVING),
            bus(VEHICLE_204003542, RUNNING_STATE_NEVER_OBSERVED),
            bus(VEHICLE_204000139, null));

        // when
        CollectedObservations actual = CollectedObservations.from(buses);

        // then
        assertThat(actual.excludedRows()).hasSize(2);
    }

    @Test
    void 쌓을_관측은_상류_응답에서_몇_번째_줄이었는지를_들고_있다() {
        // given
        List<BusLocation> buses = List.of(
            bus(VEHICLE_204000206, RUNNING_STATE_NEVER_OBSERVED),
            bus(VEHICLE_204003542, RUNNING_STATE_ARRIVED),
            bus(VEHICLE_204000139, RUNNING_STATE_DEPARTED));

        // when
        CollectedObservations actual = CollectedObservations.from(buses);

        // then
        assertThat(actual.storableRows())
            .extracting(UpstreamObservationRow::sourceRowNumber)
            .containsExactly(1, 2);
    }

    @Test
    void 차량이_한_대도_없으면_쌓을_관측도_없다() {
        // when
        CollectedObservations actual = CollectedObservations.from(List.of());

        // then
        assertThat(actual.storableRows()).isEmpty();
    }

    private static BusLocation bus(
        String vehicleId,
        Integer runningState
    ) {
        return new BusLocation(
            PLATE_NUMBER,
            vehicleId,
            NORMAL_BUS,
            ROUTE_3330,
            ROUTE_TYPE_11,
            STOP_205000217,
            STOP_SEQUENCE_6,
            runningState,
            SEATS_43,
            CROWD_LEVEL_3,
            TAGLESS_1);
    }
}
