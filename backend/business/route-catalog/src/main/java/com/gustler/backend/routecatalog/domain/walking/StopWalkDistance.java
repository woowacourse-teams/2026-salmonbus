package com.gustler.backend.routecatalog.domain.walking;

import com.gustler.backend.routecatalog.domain.StopCoordinates;
import java.util.Objects;

/**
 * 두 정류장 사이 실제 보행 경로 거리다. 방향이 없어서 정류장 ID가 작은 쪽을 {@code stationA}로 둔다.
 * 거리를 계산할 때 쓴 두 좌표를 함께 들고 있어, 좌표가 바뀐 정류장의 쌍을 가려낼 수 있다.
 */
public record StopWalkDistance(
    String stationA,
    String stationB,
    StopCoordinates locationA,
    StopCoordinates locationB,
    int walkMeters
) {
    public StopWalkDistance {
        Objects.requireNonNull(stationA, "stationA는 null일 수 없습니다.");
        Objects.requireNonNull(stationB, "stationB는 null일 수 없습니다.");
        Objects.requireNonNull(locationA, "locationA는 null일 수 없습니다.");
        Objects.requireNonNull(locationB, "locationB는 null일 수 없습니다.");
        if (stationA.compareTo(stationB) >= 0) {
            throw new IllegalArgumentException("stationA는 stationB보다 작아야 한다");
        }
        if (walkMeters < 0) {
            throw new IllegalArgumentException("도보 거리는 음수일 수 없다");
        }
    }
}
