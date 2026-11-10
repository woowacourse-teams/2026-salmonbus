package com.gustler.backend.api.salmon.domain;

import static com.gustler.backend.api.salmon.domain.ActionFixture.EPOCH;
import static com.gustler.backend.api.salmon.domain.ActionFixture.marginal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.api.salmon.domain.Probabilities.*;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProbabilitiesTest {
    private static final ConditionHistory BOARDED_FIRST = new ConditionHistory(EPOCH, Set.of(), Set.of(),
        Optional.of("o-4"));
    private static final ConditionHistory BOARDED_SECOND = new ConditionHistory(EPOCH, Set.of(), Set.of(),
        Optional.of("o-9"));

    @Test
    void 조건부_확률에_조건_이력이_비어_있으면_입력을_만들지_않는다() {
        for (ValueTag tag : List.of(ValueTag.COND_FAIL, ValueTag.COND_MISS)) {
            assertThatThrownBy(() -> new ProbabilityInput("s0-11", tag, Optional.empty(), 0.10))
                .as("%s", tag).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void 조건_이력이_붙은_주변확률은_조건_이력이_같은_슬롯에만_쓴다() {
        ProbabilitySupply supply = new ProbabilitySupply(List.of(
            new ProbabilityInput("b-19", ValueTag.MARG, Optional.of(BOARDED_FIRST), 0.8)));
        ConditionHistory failedAfterSecond = new ConditionHistory(EPOCH, Set.of("b-14"), Set.of(), Optional.of("o-9"));

        assertThat(supply.valueFor(new Slot("b-19", SlotKind.S_FIRST, BOARDED_FIRST))).isEqualTo(new Matched(0.8));
        assertThat(supply.valueFor(new Slot("b-19", SlotKind.S_FIRST, BOARDED_SECOND))).isEqualTo(new Absent());
        assertThat(supply.valueFor(new Slot("b-19", SlotKind.S_FAIL, failedAfterSecond))).isEqualTo(new Absent());
    }

    @Test
    void 조건_이력이_같은_주변확률을_조건_이력_없는_주변확률보다_먼저_쓴다() {
        ProbabilityInput general = marginal("b-19", 0.1);
        ProbabilityInput boardedFirst = new ProbabilityInput("b-19", ValueTag.MARG, Optional.of(BOARDED_FIRST), 0.9);
        Slot afterFirst = new Slot("b-19", SlotKind.S_FIRST, BOARDED_FIRST);
        Slot afterSecond = new Slot("b-19", SlotKind.S_FIRST, BOARDED_SECOND);

        for (List<ProbabilityInput> inputs : List.of(List.of(general, boardedFirst), List.of(boardedFirst, general))) {
            ProbabilitySupply supply = new ProbabilitySupply(inputs);

            assertThat(supply.valueFor(afterFirst)).as("%s", inputs).isEqualTo(new Matched(0.9));
            assertThat(supply.valueFor(afterSecond)).as("%s", inputs).isEqualTo(new Matched(0.1));
        }
    }
}
