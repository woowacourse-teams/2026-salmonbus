package com.gustler.backend.api.salmon.domain;

import com.gustler.backend.api.salmon.domain.ActionJudge.Baseline;
import com.gustler.backend.api.salmon.domain.ActionJudge.BaselineChanged;
import com.gustler.backend.api.salmon.domain.ActionJudge.CandidateSource;
import com.gustler.backend.api.salmon.domain.ActionJudge.Settled;
import com.gustler.backend.api.salmon.domain.Fields.MissingReason;
import com.gustler.backend.api.salmon.domain.StopLists.StopList;
import com.gustler.backend.api.salmon.domain.StopLists.Unavailable;
import com.gustler.backend.api.salmon.domain.Verdicts.Verdict;
import java.util.Optional;

/** 판정 중 지금 정류장 목록이 바뀌면 처음부터 다시 판정하고, 반복 한도를 넘으면 그 목록을 결측으로 두고 끝낸다. */
public final class ActionDecider {
    private ActionDecider() {
    }

    public interface Sources {
        Baseline baseline(String epoch, Optional<StopList> override);

        CandidateSource candidates();
    }

    public record Decision(Verdict verdict, int epochsUsed) {
    }

    public static Decision decide(Sources sources, ActionConfig config) {
        Optional<StopList> override = Optional.empty();
        for (int epochNumber = 1; epochNumber <= config.epochBudget(); epochNumber++) {
            Baseline baseline = sources.baseline("epoch-" + epochNumber, override);
            switch (ActionJudge.judgement(baseline, config, sources.candidates())) {
                case Settled settled -> {
                    return new Decision(settled.verdict(), epochNumber);
                }
                case BaselineChanged changed -> override = Optional.of(changed.update());
            }
        }
        int sealedEpoch = config.epochBudget() + 1;
        Baseline unstable = sources.baseline("epoch-" + sealedEpoch,
            Optional.of(new Unavailable(MissingReason.BASELINE_UNSTABLE)));
        return switch (ActionJudge.judgement(unstable, config, sources.candidates())) {
            case Settled settled -> new Decision(settled.verdict(), sealedEpoch);
            case BaselineChanged ignored -> throw new IllegalStateException(
                "baseline must use the override once the epoch budget is spent");
        };
    }
}
