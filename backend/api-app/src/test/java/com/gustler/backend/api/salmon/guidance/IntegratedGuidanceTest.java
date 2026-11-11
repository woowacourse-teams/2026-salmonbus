package com.gustler.backend.api.salmon.guidance;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.salmon.domain.IntegratedJudge.Terminal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IntegratedGuidanceTest {
    @Test
    void 할_행동이_정해진_상태는_확률_없는_한_문장으로_말한다() {
        assertThat(IntegratedGuidance.line(Terminal.GO)).contains("다른 정류장으로 옮기면 탈 가능성이 더 높아요.");
        assertThat(IntegratedGuidance.line(Terminal.NO_MOVE_POLICY)).contains("지금은 정류장을 옮기지 않아도 돼요.");
        assertThat(IntegratedGuidance.line(Terminal.NO_MOVE_NO_GAIN))
            .contains("가까운 정류장으로 옮겨도 크게 달라지지 않아요.");
        assertThat(IntegratedGuidance.line(Terminal.NO_MOVE_NO_CANDIDATE))
            .contains("걸어서 옮겨 탈 만한 정류장이 없어요.");
        assertThat(IntegratedGuidance.line(Terminal.NO_MOVE_INFEASIBLE))
            .isEqualTo(IntegratedGuidance.line(Terminal.NO_MOVE_NO_CANDIDATE));
        for (Terminal terminal : Terminal.values()) {
            Optional<String> line = IntegratedGuidance.line(terminal);
            assertThat(line.filter(text -> text.isBlank() || text.chars().anyMatch(Character::isDigit))).isEmpty();
        }
    }

    @Test
    void 판정하지_못한_상태는_문장이_없다() {
        assertThat(IntegratedGuidance.line(Terminal.ABSTAIN_EVIDENCE)).isEmpty();
        assertThat(IntegratedGuidance.line(Terminal.ABSTAIN_UNCERTAIN_GAIN)).isEmpty();
        assertThat(IntegratedGuidance.line(Terminal.INVALID_INPUT)).isEmpty();
        assertThat(IntegratedGuidance.line(Terminal.WINDOW_EXPIRED)).isEmpty();
    }
}
