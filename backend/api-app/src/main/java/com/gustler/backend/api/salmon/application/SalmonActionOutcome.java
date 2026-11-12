package com.gustler.backend.api.salmon.application;

import com.gustler.backend.api.salmon.domain.ActionDecider.Decision;
import java.util.List;

/** 기능이 꺼진 상태와 판정 결과를 한 호출의 반환으로 구분한다. 꺼진 상태는 판정이 아니다. */
public sealed interface SalmonActionOutcome permits SalmonActionOutcome.Disabled, SalmonActionOutcome.Decided {
    record Disabled() implements SalmonActionOutcome {
    }

    record Decided(Decision decision, List<String> guidance, SalmonDecisionRecord record) implements SalmonActionOutcome {
        public Decided {
            guidance = List.copyOf(guidance);
        }
    }
}
