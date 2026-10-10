package com.gustler.backend.forecasting.domain.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch.State;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvaluationArchiveBatchTest {
    @Test
    void 예약_상태에는_파일_검증값이_없다() {
        // given
        var id = UUID.randomUUID();

        // when
        var batch = new EvaluationArchiveBatch(id, 1, 1, UUID.randomUUID(), Instant.now(), State.RESERVED, 1, null);

        // then
        assertThat(batch.manifestSha256()).isNull();
    }

    @Test
    void 검증_완료에는_올바른_파일_검증값이_필요하다() {
        // given
        var id = UUID.randomUUID();

        // when & then
        assertThatThrownBy(() -> new EvaluationArchiveBatch(id, 1, 1, UUID.randomUUID(), Instant.now(),
            State.VERIFIED, 1, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvaluationArchiveBatch(id, 1, 1, UUID.randomUUID(), Instant.now(),
            State.RESERVED, 1, "a".repeat(64))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 비어있거나_처리_한도를_넘는_묶음은_만들지_않는다() {
        // given
        var id = UUID.randomUUID();

        // when & then
        assertThatThrownBy(() -> new EvaluationArchiveBatch(id, 1, 1, UUID.randomUUID(), Instant.now(),
            State.RESERVED, 0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvaluationArchiveBatch(id, 1, 1, UUID.randomUUID(), Instant.now(),
            State.RESERVED, 101, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
