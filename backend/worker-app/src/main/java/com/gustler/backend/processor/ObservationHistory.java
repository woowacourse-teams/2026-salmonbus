package com.gustler.backend.processor;

import java.util.List;

/**
 * 한 노선 판본의 최근 판을 오래된 것부터 늘어놓은 것. 맨 뒤가 궤적을 낼 대상 판이다.
 *
 * <p>판을 빠짐없이 늘어놓아야 뜻이 선다. 중간을 걸러내면 판 결손과 구분이 안 된다.
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
