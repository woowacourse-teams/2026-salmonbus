package com.gustler.backend.forecasting.support;

import com.gustler.backend.forecasting.application.forecast.ScoringReasons;
import java.util.Map;
import java.util.Optional;

/**
 * 채점의 사유를 기대값 파일({@link Baseline})에 적힌 사유 이름으로 바꿔 읽는 표다.
 *
 * <p>채점은 놓친 예측의 사유를 원인별로 나눠 내는데, 기대값 파일에는 한 이름으로 적혀 있다. 정산 근거가 없거나 계산이 실패한
 * 사유는 기대값 파일에 없다. 그 밖의 사유는 이름이 같다.
 */
public final class BaselineReasons {

    private static final String LOST = "NO_CLOSED_IDENTITY_SAFE_PASS";
    private static final Map<String, Optional<String>> RECORDED_NAMES = Map.of(
            ScoringReasons.TARGET_WITHOUT_PASS,
            Optional.of(LOST),
            ScoringReasons.PASS_CONDITION_UNMET,
            Optional.of(LOST),
            ScoringReasons.CHAIN_BROKEN,
            Optional.of(LOST),
            ScoringReasons.PASS_NOT_CLOSED,
            Optional.of(LOST),
            ScoringReasons.SETTLEMENT_EVIDENCE_MISSING,
            Optional.empty(),
            ScoringReasons.SETTLEMENT_CALCULATION_FAILED,
            Optional.empty()
    );

    private BaselineReasons() {
    }

    /**
     * 사유를 기대값 파일의 이름으로 바꾼다.
     *
     * @param reason 채점의 사유
     * @return 기대값 파일에 적힌 이름. 기대값 파일에 없는 사유면 빈 값
     */
    public static Optional<String> recordedName(String reason) {
        return RECORDED_NAMES.getOrDefault(reason, Optional.ofNullable(reason));
    }
}
