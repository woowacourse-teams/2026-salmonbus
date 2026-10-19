package com.gustler.backend.routecatalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HeadwayRangeTest {

    @ParameterizedTest
    @CsvSource({"10, 20", "15, 15", "1, 1440"})
    void 최소가_최대보다_크지_않은_1분부터_1440분까지의_범위를_받는다(
        final int minMinutes,
        final int maxMinutes
    ) {
        // when
        Optional<HeadwayRange> actual = HeadwayRange.of(minMinutes, maxMinutes);

        // then
        assertThat(actual).contains(new HeadwayRange(minMinutes, maxMinutes));
    }

    @ParameterizedTest
    @CsvSource(value = {"0, 0", "0, 20", "-5, 20", "20, 10", "10, 1441", "null, 20", "10, null", "null, null"},
        nullValues = "null")
    void 영분_음수_역전_하루_초과_한쪽_누락은_배차간격으로_받지_않는다(
        Integer minMinutes,
        Integer maxMinutes
    ) {
        // when
        Optional<HeadwayRange> actual = HeadwayRange.of(minMinutes, maxMinutes);

        // then
        assertThat(actual).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"0, 10", "20, 10", "10, 1441"})
    void 성립하지_않는_범위로_직접_만들면_거부한다(
        final int minMinutes,
        final int maxMinutes
    ) {
        // when & then
        assertThatThrownBy(() -> new HeadwayRange(minMinutes, maxMinutes))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
