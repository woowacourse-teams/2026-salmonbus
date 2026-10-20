package com.gustler.backend.forecasting.domain.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch.State;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvaluationArchiveBatchTest {
    @Test
    void 예약_상태에는_파일_검증값이_없다() {
        // given
        UUID id = UUID.randomUUID();

        // when
        EvaluationArchiveBatch batch =
            new EvaluationArchiveBatch(id, 1, 1, UUID.randomUUID(), Instant.now(), State.RESERVED, 1, null);

        // then
        assertThat(batch.manifestSha256()).isNull();
    }

    @Test
    void 검증_완료에는_올바른_파일_검증값이_필요하다() {
        // given
        UUID id = UUID.randomUUID();

        // when
        Throwable caughtFailure = catchThrowable(
            () -> new EvaluationArchiveBatch(id, 1, 1, UUID.randomUUID(), Instant.now(),
            State.VERIFIED, 1, null));

        // then
        assertThat(caughtFailure)
            .isNotNull().isInstanceOf(IllegalArgumentException.class);

        // when
        Throwable phaseFailure1 = catchThrowable(
            () -> new EvaluationArchiveBatch(id, 1, 1, UUID.randomUUID(), Instant.now(),
            State.RESERVED, 1, "a".repeat(64)));

        // then
        assertThat(phaseFailure1)
            .isNotNull().isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 비어있거나_처리_한도를_넘는_묶음은_만들지_않는다() {
        // given
        UUID id = UUID.randomUUID();

        // when
        Throwable caughtFailure = catchThrowable(
            () -> new EvaluationArchiveBatch(id, 1, 1, UUID.randomUUID(), Instant.now(),
            State.RESERVED, 0, null));

        // then
        assertThat(caughtFailure)
            .isNotNull().isInstanceOf(IllegalArgumentException.class);

        // when
        Throwable phaseFailure1 = catchThrowable(
            () -> new EvaluationArchiveBatch(id, 1, 1, UUID.randomUUID(), Instant.now(),
            State.RESERVED, 1001, null));

        // then
        assertThat(phaseFailure1)
            .isNotNull().isInstanceOf(IllegalArgumentException.class);
    }
}
