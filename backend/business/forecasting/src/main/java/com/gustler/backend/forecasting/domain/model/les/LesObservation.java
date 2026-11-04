package com.gustler.backend.forecasting.domain.model.les;

import java.time.Instant;

/** 수집 시각과 원자료 식별자를 보존한 Les 입력 관측. */
public record LesObservation(
    long observationId,
    long batchId,
    long batchRank,
    Instant receivedAt,
    String vehicleId,
    int passedStopOrder,
    int stopOrder,
    int state,
    Integer seats,
    int crowd
) {

    /** samples3의 categorical code -1. 실제 차량 식별자로 사용하지 않는다. */
    public static final String ANONYMOUS_GROUP = "\uffffles-anonymous-observations";

    public boolean hasVehicleIdentity() {
        return vehicleId != null && !vehicleId.equals(ANONYMOUS_GROUP);
    }
}
