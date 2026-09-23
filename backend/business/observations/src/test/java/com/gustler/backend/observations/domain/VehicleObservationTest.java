package com.gustler.backend.observations.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class VehicleObservationTest {
    private static final String VEHICLE_204000206 = "204000206";
    private static final String PLATE_NUMBER = "경기70아0001";
    private static final String STOP_205000217 = "205000217";
    private static final int STOP_SEQUENCE_6 = 6;
    private static final int ROUTE_TYPE_11 = 11;
    private static final int TAGLESS_1 = 1;
    private static final int NORMAL_BUS = 0;
    private static final int RUNNING_STATE_DEPARTED = 2;
    private static final int CROWD_LEVEL_3 = 3;

    @Test
    void 관측에는_잔여석이_아는_것이든_모르는_것이든_들어간다() {
        // when & then
        assertThatThrownBy(() -> new VehicleObservation(
            VEHICLE_204000206,
            PLATE_NUMBER,
            STOP_SEQUENCE_6,
            STOP_205000217,
            RUNNING_STATE_DEPARTED,
            null,
            CROWD_LEVEL_3,
            NORMAL_BUS,
            ROUTE_TYPE_11,
            TAGLESS_1))
            .isInstanceOf(NullPointerException.class);
    }
}
