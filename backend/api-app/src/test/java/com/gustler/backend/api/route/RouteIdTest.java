package com.gustler.backend.api.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RouteIdTest {

    @ParameterizedTest
    @ValueSource(strings = {"204000057", "234000050", "000000001"})
    void 아홉_자리_숫자_문자열을_노선_ID로_만든다(String value) {
        // when
        RouteId routeId = new RouteId(value);

        // then
        assertThat(routeId.value()).isEqualTo(value);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "12345678", "1234567890", "12345678a", "-204000057"})
    void 아홉_자리_숫자가_아니면_노선_ID로_만들지_않는다(String value) {
        // when & then
        assertThatThrownBy(() -> new RouteId(value))
            .isInstanceOf(InvalidRouteIdException.class)
            .hasMessage("routeId는 9자리 숫자여야 합니다.");
    }
}
