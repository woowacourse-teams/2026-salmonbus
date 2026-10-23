package com.gustler.backend.quota.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.gustler.backend.quota.api.CallQuota;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.assertj.core.api.AutoCloseableSoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DailyCallQuotaTest {
    private static final OffsetDateTime BEFORE_MIDNIGHT = OffsetDateTime.parse("2026-08-28T14:59:59Z");
    private static final OffsetDateTime MIDNIGHT = OffsetDateTime.parse("2026-08-28T15:00:00Z");

    @Test
    void 한국_자정을_기준으로_호출_날짜가_바뀐다() {
        // when
        DailyCallQuota before = DailyCallQuota.at(CallQuota.BUS_LOCATION, "a", BEFORE_MIDNIGHT, 3);
        DailyCallQuota after = DailyCallQuota.at(CallQuota.BUS_LOCATION, "a", MIDNIGHT, 3);

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(before.koreanDate()).isEqualTo(LocalDate.of(2026, 8, 28));
            softly.assertThat(after.koreanDate()).isEqualTo(LocalDate.of(2026, 8, 29));
        }
        assertThat(before.covers(MIDNIGHT)).isFalse();
    }

    @Test
    void 시각의_오프셋이_달라도_한국_날짜가_같으면_기존_예약을_쓴다() {
        // when
        DailyCallQuota quota = DailyCallQuota.at(CallQuota.BUS_LOCATION, "a", BEFORE_MIDNIGHT, 3);

        // then
        assertThat(quota.covers(OffsetDateTime.parse("2026-08-28T23:59:59+09:00"))).isTrue();
    }

    @Test
    void 여러_호출은_요청한_횟수_전체를_한번에_예약한다() {
        // when
        DailyCallQuota quota = DailyCallQuota.at(CallQuota.BUS_ROUTE, "a", BEFORE_MIDNIGHT, 2);

        // then
        assertThat(quota.reservationFor(2)).contains(new DailyCallQuota.Reservation(quota, 2));
    }

    @Test
    void 설정된_하루_한도를_초과하는_요청은_예약하지_않는다() {
        // when
        DailyCallQuota quota = DailyCallQuota.at(CallQuota.BUS_ROUTE, "a", BEFORE_MIDNIGHT, 1);

        // then
        assertThat(quota.reservationFor(2)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void 호출_횟수는_양수여야_한다(final int calls) {
        // when
        DailyCallQuota quota = DailyCallQuota.at(CallQuota.BUS_ROUTE, "a", BEFORE_MIDNIGHT, 3);

        // then

        // when
        Throwable phaseFailure1 = catchThrowable(() -> quota.reservationFor(calls));

        // then
        assertThat(phaseFailure1)
            .isNotNull().isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void 하루_한도는_양수여야_한다(final int limit) {
        // when
        Throwable caughtFailure = catchThrowable(
            () -> DailyCallQuota.at(CallQuota.BUS_LOCATION, "a", BEFORE_MIDNIGHT, limit));

        // then
        assertThat(caughtFailure)
            .isNotNull().isInstanceOf(IllegalArgumentException.class);
    }
}
