package com.gustler.backend.quota.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DailyCallQuotaTest {

    private static final OffsetDateTime BEFORE_MIDNIGHT = OffsetDateTime.parse("2026-08-28T14:59:59Z");
    private static final OffsetDateTime MIDNIGHT = OffsetDateTime.parse("2026-08-28T15:00:00Z");

    @Test
    void 한국_자정을_기준으로_호출_날짜가_바뀐다() {
        DailyCallQuota before = DailyCallQuota.at(CallQuota.BUS_LOCATION, BEFORE_MIDNIGHT, 3);
        DailyCallQuota after = DailyCallQuota.at(CallQuota.BUS_LOCATION, MIDNIGHT, 3);

        assertThat(before.koreanDate()).isEqualTo(LocalDate.of(2026, 8, 28));
        assertThat(after.koreanDate()).isEqualTo(LocalDate.of(2026, 8, 29));
        assertThat(before.covers(MIDNIGHT)).isFalse();
    }

    @Test
    void 시각의_오프셋이_달라도_한국_날짜가_같으면_기존_예약을_쓴다() {
        DailyCallQuota quota = DailyCallQuota.at(CallQuota.BUS_LOCATION, BEFORE_MIDNIGHT, 3);

        assertThat(quota.covers(OffsetDateTime.parse("2026-08-28T23:59:59+09:00"))).isTrue();
    }

    @Test
    void 여러_호출은_요청한_횟수_전체를_한번에_예약한다() {
        DailyCallQuota quota = DailyCallQuota.at(CallQuota.BUS_ROUTE, BEFORE_MIDNIGHT, 2);

        assertThat(quota.reservationFor(2)).contains(new DailyCallQuota.Reservation(quota, 2));
    }

    @Test
    void 설정된_하루_한도를_초과하는_요청은_예약하지_않는다() {
        DailyCallQuota quota = DailyCallQuota.at(CallQuota.BUS_ROUTE, BEFORE_MIDNIGHT, 1);

        assertThat(quota.reservationFor(2)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void 호출_횟수는_양수여야_한다(int calls) {
        DailyCallQuota quota = DailyCallQuota.at(CallQuota.BUS_ROUTE, BEFORE_MIDNIGHT, 3);

        assertThatIllegalArgumentException().isThrownBy(() -> quota.reservationFor(calls));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void 하루_한도는_양수여야_한다(int limit) {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> DailyCallQuota.at(CallQuota.BUS_LOCATION, BEFORE_MIDNIGHT, limit));
    }
}
