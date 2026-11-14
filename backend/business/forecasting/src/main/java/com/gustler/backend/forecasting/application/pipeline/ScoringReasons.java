package com.gustler.backend.forecasting.application.pipeline;

import java.util.Map;
import java.util.Optional;

/**
 * 새 채점 단계가 쓰는 정답 판·사유 이름과, 옛 정산 결과와 대조할 때 쓰는 사유 대응표다.
 *
 * <p>옛 확정 판 정산의 사유 이름은 그대로 쓴다. 새로 나눈 사유는 {@link #legacyReason(String)}이 옛 이름으로 바꾼다. 옛 정산에
 * 대응하는 결과가 없는 사유(옛 정산은 예외로 노선 정산을 멈춘다)는 빈 값을 돌려준다. 대응표는 이 클래스 한 곳에만 둔다.
 */
public final class ScoringReasons {
    /** 새 채점이 내는 정답 판. 옛 확정 판 예보의 {@code label_version}과 같다. */
    public static final String LABEL_VERSION = "alighting-confirmed-capacity-snapshot-v3";

    /** 목표가 실정류장 목록의 첫·마지막이거나 실정류장이 아니어서 통과가 생길 수 없다. */
    public static final String TARGET_WITHOUT_PASS = "TARGET_WITHOUT_PASS";
    /** 같은 연결의 통과가 닫혔으나 기준 시각 10초 뒤 좌석 있는 도달 관측이 없거나 2시간 뒤에 닫혔다. */
    public static final String PASS_CONDITION_UNMET = "PASS_CONDITION_UNMET";
    /** 기다리는 동안 그 차량의 연결이 바뀌었다. */
    public static final String CHAIN_BROKEN = "CHAIN_BROKEN";
    /** 2시간 안에 같은 연결의 통과가 닫히지 않았다. */
    public static final String PASS_NOT_CLOSED = "PASS_NOT_CLOSED";
    /** 모델 배포에 그 노선 판본·목표 정류장의 정산 근거가 없다. */
    public static final String SETTLEMENT_EVIDENCE_MISSING = "SETTLEMENT_EVIDENCE_MISSING";
    /** 같은 통과·같은 정원에 서로 다른 근거로 계산한 정답이 둘 이상이라 저장 키 하나에 담지 못했다. */
    public static final String LABEL_EVIDENCE_CONFLICT = "LABEL_EVIDENCE_CONFLICT";
    /** 계수나 관측이 계산식의 전제를 어겨 계산이 실패했다. */
    public static final String SETTLEMENT_CALCULATION_FAILED = "SETTLEMENT_CALCULATION_FAILED";

    /** 근거의 입력 판이 확정 판이 아니다(옛 사유 2). */
    public static final String INPUT_PROFILE_MISMATCH = "INPUT_PROFILE_MISMATCH";
    /** 근거의 정원 정책이 차량 정원 스냅숏이 아니다(옛 사유 3). */
    public static final String CAPACITY_POLICY_MISMATCH = "CAPACITY_POLICY_MISMATCH";
    /** 그날 관측이 상한을 넘었다(옛 사유 4). */
    public static final String OBSERVATION_INPUT_LIMIT = "OBSERVATION_INPUT_LIMIT";
    /** 그날 저장 줄 수와 실제 줄 수가 다른 배치가 있었다(옛 사유 5). */
    public static final String SOURCE_HISTORY_INCOMPLETE = "SOURCE_HISTORY_INCOMPLETE";

    /** 옛 정산이 놓친 예측에 쓰던 사유. */
    public static final String LEGACY_LOST = "NO_CLOSED_IDENTITY_SAFE_PASS";

    private static final Map<String, Optional<String>> LEGACY = Map.of(
        TARGET_WITHOUT_PASS, Optional.of(LEGACY_LOST),
        PASS_CONDITION_UNMET, Optional.of(LEGACY_LOST),
        CHAIN_BROKEN, Optional.of(LEGACY_LOST),
        PASS_NOT_CLOSED, Optional.of(LEGACY_LOST),
        SETTLEMENT_EVIDENCE_MISSING, Optional.empty(),
        LABEL_EVIDENCE_CONFLICT, Optional.empty(),
        SETTLEMENT_CALCULATION_FAILED, Optional.empty());

    private ScoringReasons() {
    }

    /**
     * 새 사유를 옛 정산의 사유 이름으로 바꾼다. 대응표에 없는 사유는 옛 이름과 같다.
     *
     * @param reason 새 채점의 사유
     * @return 옛 사유. 옛 정산에 대응하는 결과가 없으면 빈 값
     */
    public static Optional<String> legacyReason(String reason) {
        return LEGACY.getOrDefault(reason, Optional.ofNullable(reason));
    }
}
