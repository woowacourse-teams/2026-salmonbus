package com.gustler.backend.api.boarding.domain;

/**
 * 기준 방문에서 본 후보 방문의 관계다.
 *
 * <p>같은 정류장의 다른 방문은 {@link #SAME_STATION}, 방향이 다르면 {@link #OPPOSITE}, 같은 방향이면 같은 회차에서
 * 버스가 기준보다 먼저 지나는지({@link #BEFORE}) 나중에 지나는지({@link #AFTER})로 나눈다.
 * {@link #OPPOSITE}는 방향이 다르다는 사실만 뜻하고, 그 정류장에서 타고 기준 정류장 쪽으로 돌아올 수 있는지는 약속하지 않는다.
 */
public enum StopRelation {
    BEFORE, AFTER, OPPOSITE, SAME_STATION;

    public static StopRelation of(StopVisit origin, StopVisit target) {
        if (target.stationId().equals(origin.stationId())) {
            return SAME_STATION;
        }
        if (!target.direction().equals(origin.direction())) {
            return OPPOSITE;
        }
        return target.stopSequence() < origin.stopSequence() ? BEFORE : AFTER;
    }
}
