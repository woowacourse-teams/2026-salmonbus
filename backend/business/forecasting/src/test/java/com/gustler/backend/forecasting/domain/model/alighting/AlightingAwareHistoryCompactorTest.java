package com.gustler.backend.forecasting.domain.model.alighting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AlightingAwareHistoryCompactorTest {
    private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");

    @Test
    void 이전_정원과_연속_만석_횟수를_체크포인트에_보존한다() {
        AlightingAwareObservation cap = observation(1, 0, NOW.minusSeconds(86400), 1, 68);
        AlightingAwareObservation full = observation(2, 1, NOW.minusSeconds(7200), 3, 0);
        AlightingAwareObservation source = observation(3, 2, NOW, 4, 0);
        AlightingAwareHistoryCompactor.History raw = new AlightingAwareHistoryCompactor.History(Map.of(), List.of(cap, full, source),
            List.of());
        AlightingAwareHistoryCompactor.History compact = AlightingAwareHistoryCompactor.compact(raw, NOW);
        assertSoftly(softly -> {
            softly.assertThat(compact.observations()).containsExactly(source);
            softly.assertThat(compact.prefix().get("bus").capacity()).isEqualTo(68);
        });
        AlightingAwareFeatureVector expected = new AlightingAwareFeatureSnapshot(1, source, statistics(), raw.observations(), raw.events())
            .forTarget(5);
        AlightingAwareFeatureVector restored = new AlightingAwareFeatureSnapshot(1, source, statistics(), compact.observations(),
            compact.events(), compact.prefix()).forTarget(5);
        assertSoftly(softly -> {
            softly.assertThat(expected.seat88()[14]).isEqualTo(2);
            softly.assertThat(restored.seat88()).containsExactly(expected.seat88());
            softly.assertThat(restored.probability80()).containsExactly(expected.probability80());
        });
    }

    @Test
    void 오래_정차한_차량의_통과_확정_근거를_보존한다() {
        List<AlightingAwareObservation> rows = new ArrayList<>();
        for (int i = 0; i <= 100; i++) {
            rows.add(observation(i + 1, i, NOW.minusSeconds((100 - i) * 60L), passedAt(i), 20));
        }
        AlightingAwareHistoryCompactor.History compact = AlightingAwareHistoryCompactor
            .compact(new AlightingAwareHistoryCompactor.History(Map.of(), rows, List.of()), NOW);
        assertThat(compact.observations()).containsExactlyElementsOf(rows);
        AlightingAwareObservation next = observation(102, 101, NOW.plusSeconds(60), 3, 18);
        List<AlightingAwareObservation> resumed = new ArrayList<>(compact.observations());
        resumed.add(next);
        assertThat(AlightingAwareHistoryCompactor
            .resolve(new AlightingAwareHistoryCompactor.History(compact.prefix(), resumed, compact.events()), List.of(1, 2, 3)))
            .anySatisfy(e -> {
                assertThat(e.stopOrder()).isEqualTo(2);
                assertThat(e.arrival().observationId()).isEqualTo(1);
            });
    }

    private static AlightingAwareObservation observation(
        final long id,
        final long rank,
        Instant at,
        final int p,
        final int seats
    ) {
        return new AlightingAwareObservation(id, rank + 1, rank, at, "bus", p, p, 2, seats, 0);
    }

    private static int passedAt(final int index) {
        if (index == 0) {
            return 1;
        }
        return 2;
    }

    private static AlightingAwareRouteStatistics statistics() {
        Map<String, Map<String, Double>> tables = new HashMap<String, Map<String, Double>>();
        for (String t : List.of("lam", "lam_base", "net_tab", "full_tab", "dep_tab", "stop_net_base")) {
            tables.put(t, Map.of());
        }
        List<AlightingAwareRouteStatistics.Stop> stops = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            stops.add(new AlightingAwareRouteStatistics.Stop(i, "s" + i, "정류장", false, true));
        }
        return new AlightingAwareRouteStatistics("3330", stops, 2, tables, Map.of(), Set.of());
    }
}
