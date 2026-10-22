package com.gustler.backend.api.board.application;

import com.gustler.backend.api.observation.ObservationFreshness;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 탑승판의 기준 관측 시각으로 만료 시각을 계산하고 사용 가능 여부를 판단한다.
 */
@Component
@RequiredArgsConstructor
public class BoardFreshnessPolicy {
    /**
     * 조회에서 최신으로 인정하는 관측의 최대 경과 시간이다. 심야 관측은 10분, 그 외는 5분이며
     * worker의 {@code forecast.staleness}와 함께 관리한다.
     *
     * <p><b>예보 창 ≥ 조회 창</b>의 관계를 지켜야 한다. 예보 창이 더 짧으면 두 창 사이의 관측이
     * 발행되지 않아 보드에서 조회할 최신 발행이 없어진다. 예보 창을 넓히는 것은 계약이 허용하지만,
     * 발행 큐가 관측 시각 오름차순이므로 오래된 판이 한 회차를 채우면 새 판의 발행이 밀린다.
     * 이 조회 창은 발행 지연을 막지 못하며, 지연된 오래된 판을 보여주지 않도록 제한한다.
     *
     * <p>모듈이 분리되어 상수는 공유하지 않는다. 두 값의 관계는 BoardFreshnessContractTest가
     * worker의 실행 JAR을 읽어 검증한다.
     */
    static final Duration FRESHNESS_WINDOW = ObservationFreshness.MAX_WINDOW;

    private final Clock clock;

    public OffsetDateTime staleAt(
        OffsetDateTime observedAt
    ) {
        return ObservationFreshness.staleAt(observedAt);
    }

    public boolean isStale(
        OffsetDateTime observedAt
    ) {
        return OffsetDateTime.now(clock).isAfter(staleAt(observedAt));
    }
}
