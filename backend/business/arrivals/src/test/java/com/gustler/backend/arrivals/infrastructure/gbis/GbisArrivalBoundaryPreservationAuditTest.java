package com.gustler.backend.arrivals.infrastructure.gbis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gustler.backend.arrivals.domain.ArrivalSource.Failed;
import com.gustler.backend.arrivals.domain.ArrivalSource.Reason;
import com.gustler.backend.arrivals.domain.ArrivalSource;
import com.gustler.backend.arrivals.domain.ArrivalSource.Success;
import com.gustler.backend.gbis.api.GbisApiCaller;
import com.gustler.backend.gbis.api.GbisHttpResult;
import com.gustler.backend.gbis.api.GbisRawResponse.NotReceived;
import com.gustler.backend.gbis.api.GbisRawResponse.Received;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class GbisArrivalBoundaryPreservationAuditTest {

    private final GbisApiCaller caller = mock(GbisApiCaller.class);
    private final GbisArrivalSource source = new GbisArrivalSource(caller, new ObjectMapper(),
        Clock.fixed(Instant.parse("2026-10-20T03:00:00Z"), ZoneOffset.UTC));

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"busArrivalList\":null}", "{\"busArrivalList\":[]}"})
    void 정상_본문에서_목록_누락과_null과_빈배열은_정상_빈수신이다(String body) {
        // given
        String response = "{\"response\":{\"msgHeader\":{\"resultCode\":0},\"msgBody\":" + body + "}}";
        when(caller.getByStationWithMetadata(anyString(), anyString(), anyString()))
            .thenReturn(new GbisHttpResult(new Received(response), 200, null));

        // when
        ArrivalSource.Result actual = source.fetch("206000637", "a");

        // then
        assertThat(actual).isInstanceOf(Success.class);
        assertThat(((Success) actual).routes()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("retryAfterValues")
    void HTTP날짜와_초단위와_해석불가_재시도_힌트를_구분한다(String header, Duration expected) {
        // given
        when(caller.getByStationWithMetadata(anyString(), anyString(), anyString()))
            .thenReturn(new GbisHttpResult(new NotReceived("empty"), 429, header));

        // when
        ArrivalSource.Result actual = source.fetch("206000637", "a");

        // then
        assertThat(actual).isEqualTo(new Failed(Reason.RATE_LIMITED, expected));
    }

    private static Stream<Arguments> retryAfterValues() {
        return Stream.of(
            Arguments.of("Tue, 20 Oct 2026 03:02:00 GMT", Duration.ofSeconds(120)),
            Arguments.of("Tue, 20 Oct 2026 02:59:59 GMT", Duration.ZERO),
            Arguments.of(" 120 ", Duration.ofSeconds(120)),
            Arguments.of("9223372036854775807", null),
            Arguments.of("broken", null));
    }
}
