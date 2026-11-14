package com.gustler.backend.forecasting.application.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ScoringReasonsTest {
    @Test
    void 새로_나눈_놓침_사유는_옛_정산의_닫힌_통과_없음으로_바꾼다() {
        for (String reason : new String[] {ScoringReasons.TARGET_WITHOUT_PASS, ScoringReasons.PASS_CONDITION_UNMET,
            ScoringReasons.CHAIN_BROKEN, ScoringReasons.PASS_NOT_CLOSED}) {
            assertThat(ScoringReasons.legacyReason(reason)).contains("NO_CLOSED_IDENTITY_SAFE_PASS");
        }
    }

    @Test
    void 옛_정산이_예외로_멈추던_경우의_사유는_대응하는_옛_사유가_없다() {
        for (String reason : new String[] {ScoringReasons.SETTLEMENT_EVIDENCE_MISSING,
            ScoringReasons.LABEL_EVIDENCE_CONFLICT, ScoringReasons.SETTLEMENT_CALCULATION_FAILED}) {
            assertThat(ScoringReasons.legacyReason(reason)).isEmpty();
        }
    }

    @Test
    void 옛_정산과_같은_이름의_사유는_그대로_둔다() {
        for (String reason : new String[] {"UNSUPPORTED_HEADWAY", "NO_PREVIOUS_PASS", "OUTSIDE_FITTED_BAND",
            ScoringReasons.SOURCE_HISTORY_INCOMPLETE, ScoringReasons.OBSERVATION_INPUT_LIMIT}) {
            assertThat(ScoringReasons.legacyReason(reason)).contains(reason);
        }
    }
}
