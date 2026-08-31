package com.gustler.backend.collector;

import java.time.OffsetDateTime;

/**
 * 예보가 이미 끝난 묶음을 같은 계획으로 다시 열려 했다.
 *
 * <p>IllegalStateException 을 안 쓴다. 리포지터리 빈에서 나가면 스프링이 그것을
 * InvalidDataAccessApiUsageException 으로 바꿔서, 부르는 쪽이 수집 규칙 위반인지
 * 영속성 오류인지 못 가린다.
 */
public class ForecastAlreadyCompletedException extends RuntimeException {

    public ForecastAlreadyCompletedException(
        final long batchId,
        OffsetDateTime forecastCompletedAt
    ) {
        super("예보가 %s 에 끝난 묶음 %d 는 다시 열 수 없다".formatted(forecastCompletedAt, batchId));
    }
}
