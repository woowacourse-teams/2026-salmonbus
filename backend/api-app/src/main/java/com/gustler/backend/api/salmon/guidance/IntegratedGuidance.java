package com.gustler.backend.api.salmon.guidance;

import com.gustler.backend.api.salmon.domain.IntegratedJudge.Terminal;
import java.util.Optional;

/**
 * v2 종료 상태를 사용자가 할 행동으로 말한다. 확률 숫자는 넣지 않는다.
 *
 * <p>판정하지 못한 상태는 사용자가 할 행동이 없으므로 문장이 없다.
 */
public final class IntegratedGuidance {
    private IntegratedGuidance() {
    }

    public static Optional<String> line(Terminal terminal) {
        return switch (terminal) {
            case GO -> Optional.of("다른 정류장으로 옮기면 탈 가능성이 더 높아요.");
            case NO_MOVE_POLICY -> Optional.of("지금은 정류장을 옮기지 않아도 돼요.");
            case NO_MOVE_NO_GAIN -> Optional.of("가까운 정류장으로 옮겨도 크게 달라지지 않아요.");
            case NO_MOVE_NO_CANDIDATE, NO_MOVE_INFEASIBLE -> Optional.of("걸어서 옮겨 탈 만한 정류장이 없어요.");
            case ABSTAIN_EVIDENCE, ABSTAIN_UNCERTAIN_GAIN, INVALID_INPUT, WINDOW_EXPIRED -> Optional.empty();
        };
    }
}
