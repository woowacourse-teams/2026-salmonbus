package com.gustler.backend.observations.domain;

import java.time.Instant;
import java.util.List;

/** 이번 호출에서 새로 저장한 관측의 식별 정보. 정상 응답의 관측이 없어도 저장 결과는 남는다. */
public record StoredObservations(long batchId, long routeVersionId, Instant observedAt, List<Row> rows) {
    public StoredObservations {
        rows = List.copyOf(rows);
    }

    public record Row(long observationId, String vehicleId, Integer remainingSeats) {
    }
}
