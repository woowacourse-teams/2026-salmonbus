package com.gustler.backend.forecasting.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.forecasting.application.forecast.ScoringReasons;
import org.junit.jupiter.api.Test;

class BaselineReasonsTest {

    @Test
    void 원인별로_나눈_놓침_사유는_기대값_파일의_닫힌_통과_없음으로_읽는다() {
        for (String reason : new String[] {ScoringReasons.TARGET_WITHOUT_PASS, ScoringReasons.PASS_CONDITION_UNMET,
            ScoringReasons.CHAIN_BROKEN, ScoringReasons.PASS_NOT_CLOSED}) {
            assertThat(BaselineReasons.recordedName(reason)).contains("NO_CLOSED_IDENTITY_SAFE_PASS");
        }
    }

    @Test
    void 정산_근거가_없거나_계산이_실패한_사유는_기대값_파일에_없다() {
        for (String reason : new String[] {ScoringReasons.SETTLEMENT_EVIDENCE_MISSING,
            ScoringReasons.SETTLEMENT_CALCULATION_FAILED}) {
            assertThat(BaselineReasons.recordedName(reason)).isEmpty();
        }
    }

    @Test
    void 기대값_파일과_이름이_같은_사유는_그대로_읽는다() {
        for (String reason : new String[] {"UNSUPPORTED_HEADWAY", "NO_PREVIOUS_PASS", "OUTSIDE_FITTED_BAND",
            ScoringReasons.SOURCE_HISTORY_INCOMPLETE, ScoringReasons.OBSERVATION_INPUT_LIMIT}) {
            assertThat(BaselineReasons.recordedName(reason)).contains(reason);
        }
    }
}
