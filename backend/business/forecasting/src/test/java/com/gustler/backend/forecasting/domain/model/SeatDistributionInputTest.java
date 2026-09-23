package com.gustler.backend.forecasting.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.gustler.backend.forecasting.domain.model.SeatRangeException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class SeatDistributionInputTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 모델이_지원하는_최소값과_최대값을_입력하면_허용한다(boolean useMaximum) {
        // given
        int currentSeats = useMaximum ? SeatGrid.LARGEST_SEATS : 0;
        int capacity = useMaximum ? SeatGrid.LARGEST_SEATS : 1;

        // when
        Throwable actual = catchThrowable(() -> input(currentSeats, capacity));

        // then
        assertThat(actual).isNull();
    }

    @ParameterizedTest
    @MethodSource("outOfRangeInputs")
    void 좌석_범위를_벗어나면_거부한_입력값과_허용_범위를_예외에_남긴다(
        int currentSeats, int capacity, String field, int value, int minimum
    ) {
        // given
        int maximum = SeatGrid.LARGEST_SEATS;

        // when
        Throwable actual = catchThrowable(() -> input(currentSeats, capacity));

        // then
        assertThat(actual).isInstanceOfSatisfying(SeatRangeException.class, e -> {
            assertThat(e.inputField()).isEqualTo(field);
            assertThat(e.inputValue()).isEqualTo(value);
            assertThat(e.minimum()).isEqualTo(minimum);
            assertThat(e.maximum()).isEqualTo(maximum);
        });
    }

    @Test
    void 특징값이_유한하지_않으면_좌석_범위_오류와_구별해_거부한다() {
        // given
        double[] features = {Double.NaN};

        // when
        Throwable actual = catchThrowable(() -> new SeatDistributionInput(features, "3330", 1, 0, 1, null));

        // then
        assertThat(actual).isExactlyInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 노선_이름이_비어_있으면_좌석_범위_오류와_구별해_거부한다() {
        // given
        String route = "";

        // when
        Throwable actual = catchThrowable(() -> new SeatDistributionInput(new double[] {1}, route, 1, 0, 1, null));

        // then
        assertThat(actual).isExactlyInstanceOf(IllegalArgumentException.class);
    }

    private static Stream<Arguments> outOfRangeInputs() {
        int maximum = SeatGrid.LARGEST_SEATS;
        return Stream.of(
            Arguments.of(-1, maximum, "currentSeats", -1, 0),
            Arguments.of(maximum + 1, maximum + 1, "currentSeats", maximum + 1, 0),
            Arguments.of(maximum * 2, maximum * 2, "currentSeats", maximum * 2, 0),
            Arguments.of(0, 0, "capacity", 0, 1),
            Arguments.of(maximum / 2, maximum + 1, "capacity", maximum + 1, 1),
            Arguments.of(maximum / 2, maximum * 2, "capacity", maximum * 2, 1));
    }

    private SeatDistributionInput input(int currentSeats, int capacity) {
        return new SeatDistributionInput(new double[] {1}, "3330", 1, currentSeats, capacity, null);
    }
}
