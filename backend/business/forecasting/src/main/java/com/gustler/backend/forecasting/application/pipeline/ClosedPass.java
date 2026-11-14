package com.gustler.backend.forecasting.application.pipeline;

import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwarePassEvent;
import java.time.Instant;

/**
 * 배치 하나로 닫힌 정류장 통과다. 통과 사건은 옛 경로의 {@code AlightingAwareHistoryCompactor.resolve}가 만든 것을 그대로 담고,
 * 그 차량의 연결 시작 관측 번호와 "지난 정류장이 목표와 정확히 같고 좌석을 아는 관측 가운데 가장 늦은 것의 확정 시각"을 더한다.
 * 그런 관측이 없으면 마지막 값은 비어 있다. 연결 시작을 실제로 본 차량의 통과만 만든다.
 */
public record ClosedPass(
    AlightingAwarePassEvent event,
    long chainStartObservationId,
    Instant reachedKnownSeatAt
) {
}
