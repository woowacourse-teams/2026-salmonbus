package com.gustler.localgbis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StationReplayTest {
    private static final Path DATA = Path.of(System.getProperty("routes.file")).getParent();

    @Test
    void 판교역_부근_주변정류소는_실측처럼_반경_500m_안을_거리순으로_준다() throws Exception {
        StationReplay replay = StationReplay.read(DATA.resolve("station-around-20261105.json"),
            DATA.resolve("station-arrivals-20261105.json"), Instant.parse("2026-11-05T06:00:00Z"));

        List<Map<String, Object>> rows = replay.around(127.1110, 37.3940);

        assertTrue(rows.size() >= 25, "rows=" + rows.size());
        assertEquals(206000683L, rows.get(0).get("stationId"));
        assertTrue(
            rows.stream().allMatch(row -> (int) row.get("distance") <= StationReplay.AROUND_RADIUS_METERS),
            "radius"
        );
        assertTrue((int) rows.get(0).get("distance") <= (int) rows.get(rows.size() - 1).get("distance"), "order");
        assertEquals(StationReplay.DAILY_LIMIT - 1, replay.remaining());
    }

    @Test
    void 다른_노선_도착정보는_카탈로그_노선을_빼고_남은_시간을_재생_시각만큼_줄인다() throws Exception {
        Instant start = Instant.parse("2026-11-05T06:00:00Z");
        StationReplay replay = StationReplay.read(DATA.resolve("station-around-20261105.json"),
            DATA.resolve("station-arrivals-20261105.json"), start);

        List<Map<String, Object>> all = replay.otherRouteRows("206000795", Set.of(), start);
        List<Map<String, Object>> later = replay.otherRouteRows("206000795", Set.of(), start.plusSeconds(60));
        List<Map<String, Object>> withoutFirst = replay.otherRouteRows(
            "206000795",
            Set.of(String.valueOf(all.get(0).get("routeId"))),
            start
        );

        assertEquals(5, all.size());
        assertEquals(4, withoutFirst.size());
        Map<String, Object> first = all.get(0);
        Map<String, Object> firstLater = later.get(0);
        long before = ((Number) first.get("predictTimeSec1")).longValue();
        long after = ((Number) firstLater.get("predictTimeSec1")).longValue();
        assertEquals(Math.floorMod(before - 60, 900), after);
    }
}
