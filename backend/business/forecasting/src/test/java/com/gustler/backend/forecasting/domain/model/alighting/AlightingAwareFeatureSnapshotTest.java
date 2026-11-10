package com.gustler.backend.forecasting.domain.model.alighting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AlightingAwareFeatureSnapshotTest {
    private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");

    @Test
    void 공백_이전_누적_정원과_float32_입력을_보존한다() {
        AlightingAwareObservation first = observation(1, 0, NOW.minusSeconds(7200), "bus", 1, 68);
        AlightingAwareObservation source = observation(2, 1, NOW, "bus", 5, 3);
        AlightingAwareFeatureSnapshot snapshot = new AlightingAwareFeatureSnapshot(1, source, 68, statistics(), List.of(first, source),
            List.of());
        AlightingAwareFeatureVector vector = snapshot.forTarget(7);
        assertSoftly(softly -> {
            softly.assertThat(vector.seat88()).hasSize(88);
            softly.assertThat(vector.probability80()).hasSize(80);
            softly.assertThat(vector.seat88()[53]).isEqualTo(68);
            softly.assertThat(vector.seat88()[4]).isEqualTo((double) (3f / 68));
            softly.assertThat(vector.seat88()[13]).isEqualTo(1);
            softly.assertThat(vector.seat88()[84]).isEqualTo(1);
            softly.assertThat(vector.seat88()[85]).isEqualTo(1);
            softly.assertThat(vector.seat88()[87]).isEqualTo(1);
            softly.assertThat(vector.probability80()[79]).isEqualTo(2);
            softly.assertThat(vector.seat88()[81]).isEqualTo(2);
            softly.assertThat(vector.seat88()[82]).isEqualTo(3);
        });
    }

    @Test
    void 예보_시점까지_확정된_사건만_사용한다() {
        AlightingAwareObservation a = observation(1, 0, NOW.minusSeconds(30), "other", 6, 10);
        AlightingAwareObservation d = observation(2, 1, NOW.minusSeconds(20), "other", 7, 0);
        AlightingAwareObservation c = observation(3, 2, NOW.plusSeconds(1), "other", 8, 0);
        AlightingAwareObservation source = observation(4, 3, NOW, "bus", 5, 40);
        AlightingAwarePassEvent event = new AlightingAwarePassEvent("other", 1, 7, a, d, c.receivedAt(), d, c);
        AlightingAwareFeatureSnapshot noFuture = new AlightingAwareFeatureSnapshot(1, source, 40, statistics(), List.of(a, d, source, c),
            List.of(event));
        assertSoftly(softly -> {
            softly.assertThat(noFuture.forTarget(7).seat88()[57]).isEqualTo(1);
            softly.assertThat(noFuture.forTarget(7).seat88()[65]).isEqualTo(0);
        });
        AlightingAwareObservation after = observation(5, 4, NOW.plusSeconds(2), "bus", 5, 40);
        AlightingAwareFeatureSnapshot known = new AlightingAwareFeatureSnapshot(1, after, 40, statistics(), List.of(a, d, source, c, after),
            List.of(event));
        assertThat(known.forTarget(7).seat88()[75]).isEqualTo((double) (10f / 6));
    }

    @Test
    void 재시작해도_같은_입력을_복구한다() {
        List<AlightingAwareObservation> history = List.of(observation(1, 0, NOW.minusSeconds(30), "bus", 4, 40),
            observation(2, 1, NOW, "bus", 5, 0));
        AlightingAwareFeatureVector before = new AlightingAwareFeatureSnapshot(1, history.getLast(), 40, statistics(), history, List.of())
            .forTarget(7);
        AlightingAwareFeatureVector restored = new AlightingAwareFeatureSnapshot(1, history.getLast(), 40, statistics(), new ArrayList<>(history),
            List.of()).forTarget(7);
        assertSoftly(softly -> {
            softly.assertThat(restored.seat88()).containsExactly(before.seat88());
            softly.assertThat(restored.probability80()).containsExactly(before.probability80());
            softly.assertThat(before.seat88()[14]).isEqualTo(1);
        });
    }

    @Test
    void 정원_누락과_지원하지_않는_목표를_거부한다() {
        AlightingAwareObservation source = observation(1, 0, NOW, "bus", 5, 0);
        AlightingAwareFeatureSnapshot snapshot = new AlightingAwareFeatureSnapshot(1, source, 0, statistics(), List.of(source), List.of());
        assertThatThrownBy(() -> snapshot.forTarget(7)).hasMessageContaining("capacity");
        assertThatThrownBy(() -> snapshot.forTarget(5)).hasMessageContaining("target");
    }

    @Test
    void 익명_입력은_보존하고_차량_사건과_예보는_만들지_않는다() {
        String anonymous = AlightingAwareObservation.ANONYMOUS_GROUP;
        AlightingAwareObservation first = observation(1, 0, NOW.minusSeconds(40), anonymous, 4, 12);
        AlightingAwareObservation repeated = observation(2, 1, NOW.minusSeconds(20), anonymous, 4, 8);
        AlightingAwareObservation origin = observation(3, 2, NOW.minusSeconds(10), "bus", 4, 40);
        AlightingAwareObservation ahead = observation(4, 3, NOW, anonymous, 5, 3);
        AlightingAwareObservation source = observation(5, 3, NOW, "bus", 4, 40);
        List<AlightingAwareObservation> history = List.of(first, repeated, origin, ahead, source);
        AlightingAwareFeatureVector vector = new AlightingAwareFeatureSnapshot(1, source, 40, statistics(), history, List.of()).forTarget(7);
        assertThat(vector.seat88()[16]).isEqualTo((double) (12f / 40)); // The first anonymous entry survives repeated passed order.
        assertSoftly(softly -> {
            softly.assertThat(vector.seat88()[17]).isZero();
            softly.assertThat(vector.seat88()[59]).isEqualTo(.25);
            softly.assertThat(vector.seat88()[68]).isEqualTo((double) (3f / 40));
        });
        List<AlightingAwareObservation> anonymousPass = List.of(observation(6, 0, NOW.minusSeconds(40), anonymous, 1, 20),
            observation(7, 1, NOW.minusSeconds(20), anonymous, 2, 15), observation(8, 2, NOW, anonymous, 3, 10));
        assertThat(AlightingAwarePassEvents.resolve(anonymousPass, List.of(1, 2, 3))).isEmpty();
        assertThatThrownBy(() -> new AlightingAwareFeatureSnapshot(1, ahead, 40, statistics(), history, List.of()))
            .hasMessageContaining("Anonymous");
    }

    @Test
    void 반환한_배열로_내부_입력을_바꿀_수_없다() {
        AlightingAwareFeatureVector vector = new AlightingAwareFeatureVector(new double[88], new double[80]);
        vector.seat88()[0] = 99;
        assertThat(vector.seat88()[0]).isZero();
    }

    private static AlightingAwareObservation observation(
        final long id,
        final long rank,
        Instant at,
        String vid,
        final int passed,
        final int seats
    ) {
        return new AlightingAwareObservation(id, rank + 1, rank, at, vid, passed, passed + 1, 1, seats, 2);
    }

    private static String stopName(final int order) {
        if (order == 6) {
            return "경유";
        }
        return "정류장";
    }

    private static AlightingAwareRouteStatistics statistics() {
        List<AlightingAwareRouteStatistics.Stop> stops = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            stops.add(new AlightingAwareRouteStatistics.Stop(i, "s" + i, stopName(i), i > 10, i != 6));
        }
        Map<String, Map<String, Double>> tables = new HashMap<>();
        for (String n : List.of("lam", "lam_base", "net_tab", "full_tab", "dep_tab", "stop_net_base")) {
            tables.put(n, Map.of());
        }
        return new AlightingAwareRouteStatistics("3330", stops, 2, tables, Map.of("7|morning", List.of(2., 3., 1.)), Set.of());
    }
}
