package com.gustler.backend.api.salmon.application;

import com.gustler.backend.api.salmon.domain.ActionConfig;
import com.gustler.backend.api.salmon.domain.ActionDecider;
import com.gustler.backend.api.salmon.domain.ActionDecider.Decision;
import com.gustler.backend.api.salmon.domain.ActionJudge.Baseline;
import com.gustler.backend.api.salmon.domain.ActionJudge.CandidateSource;
import com.gustler.backend.api.salmon.domain.ActionJudge.Enumeration;
import com.gustler.backend.api.salmon.domain.ActionJudge.Failed;
import com.gustler.backend.api.salmon.domain.Fields.MissingReason;
import com.gustler.backend.api.salmon.domain.StopLists.StopList;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** 한 판정 기의 입력과 결과. 기록만으로 decide를 다시 돌려 같은 사유 코드를 확인한다. */
public record SalmonDecisionRecord(String routeId, String stationId, int stopSequence, Instant decisionAt,
                                   ActionConfig config, Baseline baseline, Optional<Enumeration> enumeration,
                                   Decision decision) {
    public SalmonDecisionRecord {
        Objects.requireNonNull(routeId);
        Objects.requireNonNull(stationId);
        Objects.requireNonNull(decisionAt);
        Objects.requireNonNull(config);
        Objects.requireNonNull(baseline);
        Objects.requireNonNull(enumeration);
        Objects.requireNonNull(decision);
    }

    public Decision replay() {
        Enumeration frozen = enumeration.orElseGet(() -> new Failed(MissingReason.NOT_PROVIDED));
        return ActionDecider.decide(new ActionDecider.Sources() {
            @Override
            public Baseline baseline(String epoch, Optional<StopList> override) {
                return new Baseline(epoch, override.orElse(baseline.currentStop()), baseline.supply());
            }

            @Override
            public CandidateSource candidates() {
                return epoch -> frozen;
            }
        }, config);
    }
}
