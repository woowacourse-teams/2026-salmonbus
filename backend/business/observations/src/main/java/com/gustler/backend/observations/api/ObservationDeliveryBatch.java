package com.gustler.backend.observations.api;

import com.gustler.backend.observations.api.ObservationDeliveryBatch.Kind;
import com.gustler.backend.observations.api.ObservationDeliveryBatch.Row;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** DB에 저장한 값을 전달한다. 품질 판정은 수신 측에서 확인한다. */
public record ObservationDeliveryBatch(long batchId, long routeVersionId, int attempt,
    Kind kind, Instant observedAt, List<Row> rows) {
    public ObservationDeliveryBatch {
        Objects.requireNonNull(kind);
        rows = List.copyOf(rows);
        if (attempt < 1) {
            throw new IllegalArgumentException("수집 시도 번호는 양수여야 한다");
        }
        if (kind == Kind.STORED) {
            Objects.requireNonNull(observedAt);
        }
        if (kind == Kind.CLEARED && !rows.isEmpty()) {
            throw new IllegalArgumentException("관측 삭제 전달에는 관측을 담을 수 없다");
        }
    }

    public enum Kind {
        CLEARED, STORED
    }

    public record Row(long observationId, String vehicleId, Integer remainingSeats,
        Integer passedStopOrder, Integer crowdLevel, Integer stopOrder, Integer runningState) {
    }
}
