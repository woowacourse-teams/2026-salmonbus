package com.gustler.backend.api.salmon.guidance;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.salmon.domain.IntegratedJudge.Terminal;
import org.junit.jupiter.api.Test;

class IntegratedGuidanceTest {
    @Test
    void 종료_상태마다_확률_없는_한_문장을_낸다() {
        for (Terminal terminal : Terminal.values()) {
            String line = IntegratedGuidance.line(terminal);
            assertThat(line).isNotBlank();
            assertThat(line).doesNotContain("\u2014", "\u00B7", "기다리는 편이");
            assertThat(line.chars().noneMatch(Character::isDigit)).isTrue();
        }
        assertThat(IntegratedGuidance.line(Terminal.ABSTAIN_EVIDENCE)).isEqualTo("이동과 대기를 비교할 근거가 부족해요.");
        assertThat(IntegratedGuidance.line(Terminal.GO))
            .isEqualTo("이동하면 기다리는 것보다 설정한 시간 안에 원하는 방향 진행을 확보할 가능성이 높아요.");
    }
}
