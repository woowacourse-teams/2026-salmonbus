package com.gustler.backend.api.boarding.application;

import com.gustler.backend.api.boarding.domain.AccessMode;
import com.gustler.backend.api.boarding.domain.BoardingOptions.Access;
import com.gustler.backend.api.boarding.domain.BoardingOptions.Leg;
import com.gustler.backend.api.boarding.domain.StopVisit;
import com.gustler.backend.api.boarding.domain.WalkPair;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 정적 도보 거리로 도보 한 구간짜리 이동 방법을 만든다.
 *
 * <p>같은 정류장의 다른 방문은 0m다. 쌍이 없거나, 계산에 쓴 좌표가 현재 좌표와 25m 넘게 다르면 정류장이 옮겨졌다고 보고
 * 그 후보는 결과에 넣지 않는다. 도보 시간은 거리 ÷ 걷는 속도를 초 단위로 올림한 값이다.
 */
@Component
public class WalkAccessFinder implements AccessFinder {
    static final String PEDESTRIAN_GRAPH = "PEDESTRIAN_GRAPH";
    private static final double MOVED_STOP_METERS = 25.0;
    private static final double EARTH_RADIUS_METERS = 6_371_008.8;
    private final WalkPairReader pairs;
    private final double walkSpeed;

    public WalkAccessFinder(
        WalkPairReader pairs,
        @Value("${boarding-options.walk-speed-meters-per-second:1.06}") final double walkSpeed
    ) {
        if (!(walkSpeed > 0)) {
            throw new IllegalArgumentException("걷는 속도는 양수여야 한다");
        }
        this.pairs = pairs;
        this.walkSpeed = walkSpeed;
    }

    @Override
    public Set<AccessMode> requiredModes() {
        return Set.of(AccessMode.WALK);
    }

    @Override
    public Map<StopVisit, List<Access>> find(StopVisit origin, List<StopVisit> targets, AccessConstraints constraints) {
        Map<String, WalkPair> byOther = new HashMap<>();
        for (WalkPair pair : pairs.walkPairs(origin.stationId())) {
            byOther.put(pair.other(origin.stationId()), pair);
        }
        Map<StopVisit, List<Access>> accesses = new HashMap<>();
        for (StopVisit target : targets) {
            Integer meters = walkMeters(origin, target, byOther.get(target.stationId()));
            if (meters == null) {
                continue;
            }
            final int seconds = (int) Math.ceil(meters / walkSpeed);
            if (seconds > constraints.maxSeconds()) {
                continue;
            }
            accesses.put(target, List.of(Access.of(List.of(new Leg(AccessMode.WALK, seconds, meters, PEDESTRIAN_GRAPH)))));
        }
        return accesses;
    }

    private static Integer walkMeters(StopVisit origin, StopVisit target, WalkPair pair) {
        if (target.stationId().equals(origin.stationId())) {
            return 0;
        }
        if (pair == null || moved(origin, pair) || moved(target, pair)) {
            return null;
        }
        return pair.walkMeters();
    }

    private static boolean moved(StopVisit visit, WalkPair pair) {
        if (visit.x() == null || visit.y() == null) {
            return false;
        }
        double[] stored = pair.locationOf(visit.stationId());
        return distance(visit.x(), visit.y(), stored[0], stored[1]) > MOVED_STOP_METERS;
    }

    private static double distance(final double x1, final double y1, final double x2, final double y2) {
        final double lat1 = Math.toRadians(y1);
        final double lat2 = Math.toRadians(y2);
        final double h = Math.pow(Math.sin((lat2 - lat1) / 2), 2)
            + Math.cos(lat1) * Math.cos(lat2) * Math.pow(Math.sin(Math.toRadians(x2 - x1) / 2), 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(h));
    }
}
