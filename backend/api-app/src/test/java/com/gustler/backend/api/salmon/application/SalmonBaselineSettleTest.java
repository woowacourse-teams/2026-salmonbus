package com.gustler.backend.api.salmon.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SalmonBaselineSettleTest {
    private int calls;

    @Test
    void 같은_토큰이면_두_번째_읽기에서_멈춘다() {
        assertThat(settle("a", "a", "b")).contains("a");
        assertThat(calls).isEqualTo(2);
    }

    @Test
    void 예산_끝까지_바뀌고_확인도_다르면_기준선이_없다() {
        assertThat(settle("a", "b", "c")).isEmpty();
        assertThat(calls).isEqualTo(3);
    }

    @Test
    void 확인_읽기가_직전과_같으면_그_값을_쓴다() {
        assertThat(settle("a", "b", "b")).contains("b");
        assertThat(calls).isEqualTo(3);
    }

    private Optional<String> settle(String first, String second, String third) {
        Iterator<String> reads = List.of(first, second, third).iterator();
        calls = 0;
        return SalmonBaselineSettle.untilStable(new SalmonEpochBudget(2), () -> {
            calls++;
            return reads.next();
        }, value -> value);
    }
}
