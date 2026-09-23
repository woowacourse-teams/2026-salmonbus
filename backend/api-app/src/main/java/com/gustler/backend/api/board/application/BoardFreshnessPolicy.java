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
     * 조회가 최신으로 인정하는 관측의 나이다. worker 의 {@code forecast.staleness} 와 짝이다.
     *
     * <p>관계는 등호가 아니라 <b>예보 창 ≥ 조회 창</b>이다. 예보 창이 이보다 짧으면 그 사이 관측은
     * 영영 발행되지 않아 보드가 집을 최신 발행이 없어진다. 넓히는 쪽은 계약이 허용하지만 공짜가
     * 아니다 — 발행 큐가 관측 시각 오름차순이라 오래된 판이 회차를 채워 새 판이 밀린다. 이 창은
     * 그것을 막지 못하고, 밀려서 오래된 판을 보여주지 않게 할 뿐이다.
     * 모듈이 갈려 상수를 안 나눠 쓰고, 두 값의 관계는 BoardFreshnessContractTest 가 worker 의
     * 실행 JAR 을 읽어 고정한다.
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
