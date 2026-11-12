package com.gustler.backend.api.salmon.application;

import java.util.Optional;

/**
 * 같은 방향 도보 하나. 차내 시간과 버스 도착 시각은 전광판에 없으면 비운다.
 *
 * @param transfers 같은 방향 도보는 0이다. 반대편으로 갔다가 원하는 방향 버스로 돌아오면 1이다.
 */
public record SalmonActionWalk(
    String id,
    long walkMillis,
    int transfers,
    Optional<Long> inVehicleMillis,
    Optional<Connection> connection
) {
    public SalmonActionWalk {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("walk id is required");
        }
        if (walkMillis < 0 || transfers < 0) {
            throw new IllegalArgumentException("walk time and transfers must be zero or positive");
        }
        if (inVehicleMillis == null || connection == null) {
            throw new IllegalArgumentException("walk optionals are required");
        }
        if (inVehicleMillis.filter(millis -> millis < 0).isPresent()) {
            throw new IllegalArgumentException("in-vehicle time must be zero or positive");
        }
    }

    /** 사람이 승차 위치에 서는 시각과 버스가 그 정류장에 오는 시각. 둘 다 밀리초다. */
    public record Connection(long busArrivalMillis, long personReadyMillis) {
    }
}
