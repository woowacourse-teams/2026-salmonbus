package com.gustler.backend.api.salmon.domain;

import com.gustler.backend.api.salmon.domain.ActionJudge.Baseline;
import com.gustler.backend.api.salmon.domain.ActionJudge.CandidateSource;
import com.gustler.backend.api.salmon.domain.ActionJudge.Judgement;
import com.gustler.backend.api.salmon.domain.ActionModel.MissingReason;
import com.gustler.backend.api.salmon.domain.ActionModel.StopList;
import com.gustler.backend.api.salmon.domain.ActionModel.Unavailable;
import com.gustler.backend.api.salmon.domain.ActionModel.Verdict;
import java.util.Optional;

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
            Judgement judgement = ActionJudge.judgement(baseline, config, sources.candidates());
            if (judgement.staleBaseline().isEmpty()) {
                return new Decision(judgement.verdict(), epochNumber);
            }
            override = judgement.staleBaseline();
        }
        int sealedEpoch = config.epochBudget() + 1;
        Baseline unstable = sources.baseline("epoch-" + sealedEpoch,
            Optional.of(new Unavailable(MissingReason.BASELINE_UNSTABLE)));
        return new Decision(ActionJudge.judge(unstable, config, sources.candidates()), sealedEpoch);
    }
}
