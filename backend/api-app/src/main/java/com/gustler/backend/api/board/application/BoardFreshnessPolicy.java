package com.gustler.backend.api.board.application;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BoardFreshnessPolicy {

    /**
     * 이 창은 worker 의 {@code forecast.staleness} 와 짝이다. 예보를 안 붙이는 나이와 조회가 안 쓰는
     * 나이가 같아야, 예보가 붙은 판은 곧 조회가 쓸 수 있는 판이 된다.
     *
     * <p>모듈이 갈려 상수를 안 나눠 쓴다. worker 의 {@code forecast.staleness} 는 이 창보다 짧으면 안 된다.
     * 짧으면 그 사이 판이 영영 발행되지 않아 보드가 집을 최신 발행이 없어진다.
     * 두 값의 관계는 BoardFreshnessContractTest 가 worker 의 실행 JAR 을 읽어 고정한다.
     */
    static final Duration FRESHNESS_WINDOW = Duration.ofMinutes(5);

    private final Clock clock;

    public OffsetDateTime staleAt(
        OffsetDateTime observedAt
    ) {
        return observedAt.plus(FRESHNESS_WINDOW);
    }

    public boolean isStale(
        OffsetDateTime observedAt
    ) {
        return OffsetDateTime.now(clock).isAfter(staleAt(observedAt));
    }
}
