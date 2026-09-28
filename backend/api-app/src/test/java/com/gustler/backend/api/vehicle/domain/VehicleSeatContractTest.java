package com.gustler.backend.api.vehicle.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.support.SchemaCheck;
import org.junit.jupiter.api.Test;

class VehicleSeatContractTest {

    @Test
    void 관측_표에_저장된_잔여석_미제공_사유는_같은_이름으로_응답한다() {
        // given
        var stored = SchemaCheck.allowedValues("V4__observation.sql", "seat_unknown_reason");

        // when & then
        assertThat(stored).allSatisfy(reason ->
            assertThat(VehicleSeat.from(null, reason, true)).hasToString(reason));
    }
}
