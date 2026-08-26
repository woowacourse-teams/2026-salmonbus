package com.gustler.backend.processor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SettlementTest {

    @ParameterizedTest
    @EnumSource(Settlement.class)
    void 실제_잔여석을_받아낸_예보만_채점한다(
        final Settlement settlement
    ) {
        // when
        final boolean actual = settlement.scorable();

        // then
        assertThat(actual).isEqualTo(settlement == Settlement.SETTLED);
    }
}
