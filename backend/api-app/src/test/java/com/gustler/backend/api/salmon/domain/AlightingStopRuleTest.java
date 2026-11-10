package com.gustler.backend.api.salmon.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.api.salmon.domain.AlightingStopRule.Decision;
import com.gustler.backend.api.salmon.domain.AlightingStopRule.Direction;
import org.junit.jupiter.api.Test;

class AlightingStopRuleTest {
    @Test
    void 회차_앞_반대_방향_구간은_하차까지_쓸_수_있다() {
        assertThat(AlightingStopRule.decide(44, Direction.DOWN, 10, 44)).isEqualTo(Decision.ACCEPTED);
    }

    @Test
    void 회차_뒤_반대_방향_구간은_하차까지_쓸_수_있다() {
        assertThat(AlightingStopRule.decide(44, Direction.UP, 50, 60)).isEqualTo(Decision.ACCEPTED);
    }

    @Test
    void 회차_순번에서_내리면_그_방향의_끝이고_다음_순번에서_내리면_횡단이다() {
        assertThat(AlightingStopRule.decide(44, Direction.DOWN, 43, 44)).isEqualTo(Decision.ACCEPTED);
        assertThat(AlightingStopRule.decide(44, Direction.DOWN, 44, 45)).isEqualTo(Decision.CROSSES_TURN);
    }

    @Test
    void 순번이_늘지_않으면_회차_횡단보다_먼저_거절한다() {
        assertThat(AlightingStopRule.decide(5, Direction.DOWN, 10, 3)).isEqualTo(Decision.SEQUENCE_NOT_INCREASING);
        assertThat(AlightingStopRule.decide(5, Direction.DOWN, 4, 4)).isEqualTo(Decision.SEQUENCE_NOT_INCREASING);
    }

    @Test
    void 회차_순번이_없으면_구간을_받아들이지_않는다() {
        assertThat(AlightingStopRule.decide(null, Direction.DOWN, 1, 2)).isEqualTo(Decision.TURN_UNKNOWN);
    }

    @Test
    void 원하는_방향의_차분은_재탑승_구간시간이_아니다() {
        assertThat(AlightingStopRule.decide(44, Direction.UP, 10, 20)).isEqualTo(Decision.NOT_OPPOSITE_DIRECTION);
    }

    @Test
    void 순번이_양수가_아니면_거부한다() {
        assertThatThrownBy(() -> AlightingStopRule.decide(5, Direction.DOWN, 0, 2))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AlightingStopRule.decide(0, Direction.DOWN, 1, 2))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
