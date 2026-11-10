package com.gustler.backend.api.salmon.guidance;

import com.gustler.backend.api.salmon.domain.IntegratedJudge.Terminal;

/** v2 종료 상태를 사용자에게 보일 한 문장으로 바꾼다. 확률 숫자는 넣지 않는다. */
public final class IntegratedGuidance {
    private IntegratedGuidance() {
    }

    public static String line(Terminal terminal) {
        return switch (terminal) {
            case GO -> "이동하면 기다리는 것보다 설정한 시간 안에 원하는 방향 진행을 확보할 가능성이 높아요.";
            case NO_MOVE_POLICY -> "지금 정보에서는 기다려도 실패 위험이 낮아 이동을 찾지 않아요.";
            case NO_MOVE_NO_GAIN -> "비교한 이동은 기다리는 것보다 충분히 나아지지 않아요.";
            case NO_MOVE_NO_CANDIDATE -> "평가할 이동 후보가 없어요.";
            case NO_MOVE_INFEASIBLE -> "허용 범위 안에서 갈 수 있는 이동이 없어요.";
            case ABSTAIN_EVIDENCE -> "이동과 대기를 비교할 근거가 부족해요.";
            case ABSTAIN_UNCERTAIN_GAIN -> "이득이 역치를 넘는지 범위로 확인하지 못했어요.";
            case INVALID_INPUT -> "요청 값이 서로 맞지 않아 판정하지 않았어요.";
            case WINDOW_EXPIRED -> "정한 시간이 지나 새 시간 창이 필요해요.";
        };
    }
}
