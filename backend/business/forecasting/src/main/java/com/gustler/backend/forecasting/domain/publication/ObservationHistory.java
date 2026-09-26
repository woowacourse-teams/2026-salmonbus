package com.gustler.backend.forecasting.domain.publication;

import java.util.List;

/**
 * 한 노선 버전의 최근 수집 배치를 시각 오름차순으로 보관한다. 마지막 배치가 궤적 계산 대상이다.
 *
 * <p>중간 배치를 빠뜨리지 않아야 한다. 일부를 걸러내면 실제 수집 배치가 없는 경우와 구분할 수 없다.
 */
public record ObservationHistory(
    List<ObservedBatch> batches
) {

    public ObservationHistory {
        batches = List.copyOf(batches);
        if (batches.isEmpty()) {
            throw new IllegalArgumentException("궤적을 낼 대상 판이 있어야 한다");
        }
    }

    public ObservedBatch targetBatch() {
        return batches.getLast();
    }
}
