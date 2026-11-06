package com.gustler.backend.station.contract;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 칸 하나를 주변정류소 조회(반경 500m)로 빈틈없이 덮는 탐색점 12개.
 *
 * <p>남서 모서리를 (0, 0), 북동 모서리를 (1, 1)로 둔 고정 비율이다. 위도 36.9~38.3 칸에서 칸 안 모든 지점이
 * 가장 가까운 탐색점에서 470m 이내이고, 붐비는 칸에서 49점 격자와 찾은 정류장이 같았다(2026-11-05 실측).
 * 칸 가운데에 가까운 점부터 부른다.
 */
public final class StationTileProbes {

    public static final int COUNT = 12;
    private static final double[][] FRACTIONS = {
        { -0.0019, 0.3261 },
        { -0.0019, 1.0216 },
        { 0.1988, -0.0216 },
        { 0.1988, 0.6739 },
        { 0.3996, 0.3261 },
        { 0.3996, 1.0216 },
        { 0.6004, -0.0216 },
        { 0.6004, 0.6739 },
        { 0.8012, 0.3261 },
        { 0.8012, 1.0216 },
        { 1.0019, -0.0216 },
        { 1.0019, 0.6739 }
    };

    private StationTileProbes() {
    }

    public static List<Probe> of(StationTileId tile) {
        final double width = tile.east() - tile.west();
        final double height = tile.north() - tile.south();
        final double centerX = tile.west() + width / 2;
        final double centerY = tile.south() + height / 2;
        return Arrays.stream(FRACTIONS)
            .map(fraction -> new Probe(tile.west() + fraction[0] * width, tile.south() + fraction[1] * height))
            .sorted(Comparator.comparingDouble(probe -> Math.hypot(probe.x() - centerX, probe.y() - centerY)))
            .toList();
    }

    /**
     * WGS84 경도 x, 위도 y.
     */
    public record Probe(double x, double y) {
    }
}
