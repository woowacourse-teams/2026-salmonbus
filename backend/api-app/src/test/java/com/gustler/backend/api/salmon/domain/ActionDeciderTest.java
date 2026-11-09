package com.gustler.backend.api.salmon.domain;

import static com.gustler.backend.api.salmon.domain.ActionFixture.config;
import static com.gustler.backend.api.salmon.domain.ActionFixture.entry;
import static com.gustler.backend.api.salmon.domain.ActionFixture.marginal;
import static com.gustler.backend.api.salmon.domain.ActionFixture.observed;
import static com.gustler.backend.api.salmon.domain.ActionFixture.queried;
import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.salmon.domain.ActionDecider.Decision;
import com.gustler.backend.api.salmon.domain.ActionDecider.Sources;
import com.gustler.backend.api.salmon.domain.ActionJudge.Baseline;
import com.gustler.backend.api.salmon.domain.ActionJudge.CandidateSource;
import com.gustler.backend.api.salmon.domain.ActionJudge.Listed;
import com.gustler.backend.api.salmon.domain.ActionModel.*;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ActionDeciderTest {
    private static final WalkCandidate WALK = new WalkCandidate("walk", observed(1), queried(entry("w-9", 9)),
        Optional.of(0.0));

    @Test
    void 후보_조회가_기준선_시도열을_바꾸면_새_판정_기를_열고_바뀐_기준선으로_끝낸다() {
        StopList original = queried(entry("s0-3", 3));
        StopList updated = queried(entry("s0-4", 4));
        Decision decision = ActionDecider.decide(sources(original, epoch -> Optional.of(updated)), config(15, 10));

        assertThat(decision.epochsUsed()).isEqualTo(2);
        assertThat(decision.verdict().epoch()).isEqualTo("epoch-2");
        assertThat(decision.verdict().stay().attempts()).extracting(Attempt::entryId).containsExactly("s0-4");
        assertThat(decision.verdict().code()).isEqualTo(ReasonCode.GO);
    }

    @Test
    void 기준선을_바꾸지_않는_갱신은_기록만_하고_첫_판정_기로_끝낸다() {
        StopList original = queried(entry("s0-3", 3));
        Decision decision = ActionDecider.decide(sources(original, epoch -> Optional.of(original)), config(15, 10));

        assertThat(decision.epochsUsed()).isEqualTo(1);
        assertThat(decision.verdict().code()).isEqualTo(ReasonCode.GO);
    }

    @Test
    void 판정_기_예산을_다_쓰면_불안정한_기준선을_결측으로_봉인하고_기준선_미정의로_끝낸다() {
        StopList original = queried(entry("s0-3", 3));
        int[] counter = {0};
        Function<String, Optional<StopList>> shifting = epoch ->
            Optional.of(queried(entry("s0-3", 4 + counter[0]++)));
        Decision decision = ActionDecider.decide(sources(original, shifting), config(15, 10));

        assertThat(decision.epochsUsed()).isEqualTo(4);
        assertThat(decision.verdict().code()).isEqualTo(ReasonCode.ABSTAIN_STAY_UNDEFINED);
        assertThat(decision.verdict().epoch()).isEqualTo("epoch-4");
    }

    private static Sources sources(StopList original, Function<String, Optional<StopList>> update) {
        return new Sources() {
            @Override
            public Baseline baseline(String epoch, Optional<StopList> override) {
                return new Baseline(epoch, override.orElse(original),
                    new ProbabilitySupply(List.of(marginal("s0-3", 0.3), marginal("s0-4", 0.3))));
            }

            @Override
            public CandidateSource candidates() {
                return epoch -> new Listed(List.of(WALK), new ProbabilitySupply(List.of(marginal("w-9", 0.8))),
                    update.apply(epoch));
            }
        };
    }
}
