package com.gustler.backend.forecasting.domain.model.les;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LesHistoryCompactorTest {
    private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");

    @Test
    void 이전_정원과_연속_만석_횟수를_체크포인트에_보존한다() {
        LesObservation cap = observation(1, 0, NOW.minusSeconds(86400), 1, 68);
        LesObservation full = observation(2, 1, NOW.minusSeconds(7200), 3, 0);
        LesObservation source = observation(3, 2, NOW, 4, 0);
        LesHistoryCompactor.History raw = new LesHistoryCompactor.History(Map.of(), List.of(cap, full, source),
            List.of());
        LesHistoryCompactor.History compact = LesHistoryCompactor.compact(raw, NOW);
        assertSoftly(softly -> {
            softly.assertThat(compact.observations()).containsExactly(source);
            softly.assertThat(compact.prefix().get("bus").capacity()).isEqualTo(68);
        });
        LesFeatureVector expected = new LesFeatureSnapshot(1, source, statistics(), raw.observations(), raw.events())
            .forTarget(5);
        LesFeatureVector restored = new LesFeatureSnapshot(1, source, statistics(), compact.observations(),
            compact.events(), compact.prefix()).forTarget(5);
        assertSoftly(softly -> {
            softly.assertThat(expected.seat88()[14]).isEqualTo(2);
            softly.assertThat(restored.seat88()).containsExactly(expected.seat88());
            softly.assertThat(restored.probability80()).containsExactly(expected.probability80());
        });
    }

    @Test
    void 오래_정차한_차량의_통과_확정_근거를_보존한다() {
        List<LesObservation> rows = new ArrayList<>();
        for (int i = 0; i <= 100; i++) {
            rows.add(observation(i + 1, i, NOW.minusSeconds((100 - i) * 60L), passedAt(i), 20));
        }
        LesHistoryCompactor.History compact = LesHistoryCompactor
            .compact(new LesHistoryCompactor.History(Map.of(), rows, List.of()), NOW);
        assertThat(compact.observations()).containsExactlyElementsOf(rows);
        LesObservation next = observation(102, 101, NOW.plusSeconds(60), 3, 18);
        List<LesObservation> resumed = new ArrayList<>(compact.observations());
        resumed.add(next);
        assertThat(LesHistoryCompactor
            .resolve(new LesHistoryCompactor.History(compact.prefix(), resumed, compact.events()), List.of(1, 2, 3)))
            .anySatisfy(e -> {
                assertThat(e.stopOrder()).isEqualTo(2);
                assertThat(e.arrival().observationId()).isEqualTo(1);
            });
    }

    private static LesObservation observation(
        final long id,
        final long rank,
        Instant at,
        final int p,
        final int seats) {
        return new LesObservation(id, rank + 1, rank, at, "bus", p, p, 2, seats, 0);
    }

    private static int passedAt(final int index) {
        if (index == 0) {
            return 1;
        }
        return 2;
    }

    private static LesRouteStatistics statistics() {
        Map<String, Map<String, Double>> tables = new HashMap<String, Map<String, Double>>();
        for (String t : List.of("lam", "lam_base", "net_tab", "full_tab", "dep_tab", "stop_net_base")) {
            tables.put(t, Map.of());
        }
        List<LesRouteStatistics.Stop> stops = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            stops.add(new LesRouteStatistics.Stop(i, "s" + i, "정류장", false, true));
        }
        return new LesRouteStatistics("3330", stops, 2, tables, Map.of(), Set.of());
    }
}
