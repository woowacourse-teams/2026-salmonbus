package com.gustler.backend.collector;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BoardingPolicyTest {

    @ParameterizedTest
    @CsvSource({
        // 277 두 곳은 3330 실측 위치정보 응답(location-live-snapshot.json)에서 뜬 경유 지점이다.
        "277103149, false",
        "277103157, false",
        "205000217, true",  // 범계역
        "206000055, true",
        "208000069, true",  // 안양역. 1650 과 3330 의 회차 정류소다
    })
    void 정류소_ID가_277로_시작하지_않는_곳에서만_승차할_수_있다(
        String stopId,
        final boolean expected
    ) {
        // when
        final boolean actual = BoardingPolicy.allowsBoardingAt(stopId);

        // then
        assertThat(actual).isEqualTo(expected);
    }
}
