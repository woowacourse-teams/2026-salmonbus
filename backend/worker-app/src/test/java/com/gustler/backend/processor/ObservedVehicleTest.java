package com.gustler.backend.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ObservedVehicleTest {

    private static final long ROUTE_VERSION_3330 = 1L;
    private static final Instant OBSERVED_AT = Instant.parse("2026-08-19T08:30:00+09:00");
    private static final int PASSED_STOP_44 = 44;
    private static final int SEATS_LEFT = 12;

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    void 혼잡도는_1등급부터_4등급까지다(
        final int crowdLevel
    ) {
        // when
        ObservedVehicle actual = observedWith(crowdLevel);

        // then
        assertThat(actual.crowdLevel()).isEqualTo(crowdLevel);
    }

    @Test
    void 혼잡도를_안_준_관측도_만들_수_있다() {
        // when
        ObservedVehicle actual = observedWith(null);

        // then
        assertThat(actual.hasKnownCrowdLevel()).isFalse();
    }

    @Test
    void 등급_밖의_혼잡도로는_관측을_만들_수_없다() {
        // when, then 적재가 0을 미제공으로 접어 두는데 그 규칙이 깨진 값이 오면 여기서 멈춘다
        assertThatThrownBy(() -> observedWith(0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static ObservedVehicle observedWith(
        Integer crowdLevel
    ) {
        return new ObservedVehicle(
            "204000206", ROUTE_VERSION_3330, PASSED_STOP_44, OBSERVED_AT, SEATS_LEFT, crowdLevel);
    }
}
