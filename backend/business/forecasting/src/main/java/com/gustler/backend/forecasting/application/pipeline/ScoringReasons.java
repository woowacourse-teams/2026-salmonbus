package com.gustler.backend.forecasting.application.pipeline;

import java.util.Map;
import java.util.Optional;

/**
 * 채점 단계가 쓰는 정답 판·사유 이름과, 세분한 사유를 묶은 이름으로 바꾸는 대응표다.
 *
 * <p>놓친 예측의 사유는 원인별로 나눠 저장한다. {@link #legacyReason(String)}은 나눈 사유를 하나로 묶은 이름으로 바꾸고, 묶은 이름이
 * 없는 사유는 빈 값을 돌려준다. 기준 계산과 견줄 때 쓴다. 대응표는 이 클래스 한 곳에만 둔다.
 */
public final class ScoringReasons {
    /** 채점이 내는 정답 판({@code label_version}). */
    public static final String LABEL_VERSION = "alighting-confirmed-capacity-snapshot-v3";

    /** 목표가 실정류장 목록의 첫·마지막이거나 실정류장이 아니어서 통과가 생길 수 없다. */
    public static final String TARGET_WITHOUT_PASS = "TARGET_WITHOUT_PASS";
    /**
     * 같은 연결의 통과가 닫혔으나 기준 시각 10초 뒤 좌석 있는 도달 관측이 없거나 2시간 뒤에 닫혔다. 한 연결·정류장의 통과는
     * 하나뿐이라 이 예측은 더 짝이 될 수 없으므로 통과가 닫힌 순간(또는 통과가 이미 닫힌 뒤 예보가 온 순간) 닫는다.
     */
    public static final String PASS_CONDITION_UNMET = "PASS_CONDITION_UNMET";
    /** 기다리는 동안 그 차량의 연결이 바뀌었다. */
    public static final String CHAIN_BROKEN = "CHAIN_BROKEN";
    /** 2시간 안에 같은 연결의 통과가 닫히지 않았다. */
    public static final String PASS_NOT_CLOSED = "PASS_NOT_CLOSED";
    /** 모델 배포에 그 노선 판본·목표 정류장의 정산 근거가 없다. */
    public static final String SETTLEMENT_EVIDENCE_MISSING = "SETTLEMENT_EVIDENCE_MISSING";
    /** 계수나 관측이 계산식의 전제를 어겨 계산이 실패했다. */
    public static final String SETTLEMENT_CALCULATION_FAILED = "SETTLEMENT_CALCULATION_FAILED";

    /** 근거의 입력 판이 확정 판이 아니다. */
    public static final String INPUT_PROFILE_MISMATCH = "INPUT_PROFILE_MISMATCH";
    /** 근거의 정원 정책이 차량 정원 스냅숏이 아니다. */
    public static final String CAPACITY_POLICY_MISMATCH = "CAPACITY_POLICY_MISMATCH";
    /** 그날 관측이 상한을 넘었다. */
    public static final String OBSERVATION_INPUT_LIMIT = "OBSERVATION_INPUT_LIMIT";
    /** 그날 저장 줄 수와 실제 줄 수가 다른 배치가 있었다. */
    public static final String SOURCE_HISTORY_INCOMPLETE = "SOURCE_HISTORY_INCOMPLETE";

    /** 놓친 예측의 사유를 원인으로 나누지 않고 하나로 묶은 이름. */
    public static final String LEGACY_LOST = "NO_CLOSED_IDENTITY_SAFE_PASS";

    private static final Map<String, Optional<String>> LEGACY = Map.of(
        TARGET_WITHOUT_PASS, Optional.of(LEGACY_LOST),
        PASS_CONDITION_UNMET, Optional.of(LEGACY_LOST),
        CHAIN_BROKEN, Optional.of(LEGACY_LOST),
        PASS_NOT_CLOSED, Optional.of(LEGACY_LOST),
        SETTLEMENT_EVIDENCE_MISSING, Optional.empty(),
        SETTLEMENT_CALCULATION_FAILED, Optional.empty());

    private ScoringReasons() {
    }

    /**
     * 사유를 묶은 이름으로 바꾼다. 대응표에 없는 사유는 그대로 돌려준다.
     *
     * @param reason 채점의 사유
     * @return 묶은 이름. 묶은 이름이 없는 사유면 빈 값
     */
    public static Optional<String> legacyReason(String reason) {
        return LEGACY.getOrDefault(reason, Optional.ofNullable(reason));
    }
}
