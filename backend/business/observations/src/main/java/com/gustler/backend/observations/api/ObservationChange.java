package com.gustler.backend.observations.api;

import java.util.Objects;

/** 관측 변경을 알린다. 후속 업무는 배치 식별자로 커밋된 관측을 읽는다. */
public record ObservationChange(long batchId, long routeVersionId, ObservationChange.Kind kind) {

    public ObservationChange {
        Objects.requireNonNull(kind);
    }

    public enum Kind {

        CLEARED, STORED
    }
}
