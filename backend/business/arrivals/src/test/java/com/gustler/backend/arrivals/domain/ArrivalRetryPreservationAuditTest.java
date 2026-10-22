package com.gustler.backend.arrivals.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ArrivalRetryPreservationAuditTest {

    @ParameterizedTest
    @MethodSource("retryDelays")
    void 지수대기_상한을_적용한_뒤_더_긴_원천_대기를_우선한다(
        final int failures,
        Duration retryAfter,
        Duration expected
    ) {
        // given
        ArrivalRetryPolicy policy = new ArrivalRetryPolicy(Duration.ofSeconds(40), Duration.ofSeconds(300));

        // when
        Duration actual = policy.delay(failures, retryAfter);

        // then
        assertThat(actual).isEqualTo(expected);
    }

    private static Stream<Arguments> retryDelays() {
        return Stream.of(
            Arguments.of(1, null, Duration.ofSeconds(40)),
            Arguments.of(2, null, Duration.ofSeconds(80)),
            Arguments.of(30, null, Duration.ofSeconds(300)),
            Arguments.of(30, Duration.ofSeconds(600), Duration.ofSeconds(600)),
            Arguments.of(2, Duration.ofSeconds(5), Duration.ofSeconds(80)));
    }
}
