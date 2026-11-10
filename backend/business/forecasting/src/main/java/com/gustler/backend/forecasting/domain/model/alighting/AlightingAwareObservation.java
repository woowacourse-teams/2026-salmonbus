package com.gustler.backend.forecasting.domain.model.alighting;

import java.time.Duration;
import java.time.Instant;

/**
 * 원본 판본은 receivedAt에 수신 시각을, 확인 판본은 입력 확인 시각을 보존한다.
 * 확인 판본의 물리적 수신 시각과 노선 버전/방향을 별도로 보존하며 routeVersionId=0은 원본 판본이다.
 */
public record AlightingAwareObservation(
    long observationId,
    long batchId,
    long batchRank,
    Instant receivedAt,
    String vehicleId,
    int passedStopOrder,
    int stopOrder,
    int state,
    Integer seats,
    int crowd,
    long routeVersionId,
    Instant sourceReceivedAt,
    boolean down
) {

    public AlightingAwareObservation(
        final long observationId,
        final long batchId,
        final long batchRank,
        Instant receivedAt,
        String vehicleId,
        final int passedStopOrder,
        final int stopOrder,
        final int state,
        Integer seats,
        final int crowd
    ) {
        this(observationId, batchId, batchRank, receivedAt, vehicleId, passedStopOrder, stopOrder, state, seats,
            crowd, 0, receivedAt, false);
    }

    public boolean confirmedProfile() {
        return routeVersionId > 0;
    }

    public boolean startsConfirmedTripAfter(AlightingAwareObservation previous) {
        if (previous == null || !vehicleId.equals(previous.vehicleId)) {
            return true;
        }
        return changesRun(previous) || elapsedAfter(previous) > 1800;
    }

    private boolean changesRun(AlightingAwareObservation previous) {
        return routeVersionId != previous.routeVersionId || down != previous.down
            || passedStopOrder < previous.passedStopOrder;
    }

    public boolean startsConfirmedChainAfter(AlightingAwareObservation previous) {
        if (startsConfirmedTripAfter(previous)) {
            return true;
        }
        final double physicalGap = Duration.between(previous.sourceReceivedAt, sourceReceivedAt).toNanos() / 1e9;
        return elapsedAfter(previous) > 90 || physicalGap > 90 || physicalGap < 0;
    }

    private double elapsedAfter(AlightingAwareObservation previous) {
        return Duration.between(previous.receivedAt, receivedAt).toNanos() / 1e9;
    }

    /** samples3의 categorical code -1. 실제 차량 식별자로 사용하지 않는다. */
    public static final String ANONYMOUS_GROUP = "\uffffles-anonymous-observations";

    public boolean hasVehicleIdentity() {
        return vehicleId != null && !vehicleId.equals(ANONYMOUS_GROUP);
    }
}
