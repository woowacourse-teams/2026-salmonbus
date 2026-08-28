package com.gustler.backend.collector;

import java.time.OffsetDateTime;

public record StoredRouteVersion(
    long id,
    OffsetDateTime validFrom,
    RouteVersionContent content
) {

    public void requireOpenableAt(
        OffsetDateTime readAt
    ) {
        if (!readAt.isAfter(validFrom)) {
            throw new IllegalArgumentException(
                "직전 판본이 %s 부터라 %s 에 새 판본을 열 수 없다".formatted(validFrom, readAt));
        }
    }
}
