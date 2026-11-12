package com.gustler.backend.forecasting.domain.model.alighting;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

class AlightingSettlementProjectionTest {
    private static final Instant START = Instant.parse("2026-01-05T00:00:00Z");
    private static final List<Integer> STOPS = List.of(1, 2, 3, 4, 5);

    @Test
    void 나누어_처리해도_전체_관측으로_복구한_통과_근거와_같다() {
        List<AlightingAwareObservation> rows = new ArrayList<>();
        for (int trip = 0; trip < 4; trip++) {
            for (int stop = 1; stop <= 5; stop++) {
                rows.add(row(rows.size() + 1, trip * 400 + stop * 20, stop, "a", stop % 3));
                rows.add(row(rows.size() + 1, trip * 400 + stop * 20 + 5, stop, "a", 0));
                rows.add(row(rows.size() + 1, trip * 400 + stop * 20 + 8, stop, "b", 4));
            }
        }
        rows.sort(Comparator.comparing(AlightingAwareObservation::receivedAt).thenComparingLong(AlightingAwareObservation::observationId));
        var state = AlightingSettlementProjection.Snapshot.empty();
        List<AlightingAwarePassEvent> actual = new ArrayList<>();
        for (var row : rows) {
            var projection = new AlightingSettlementProjection(new AlightingSettlementProjection.Layout(100, STOPS), 1000, state);
            projection.append(row.receivedAt(), List.of(row), false);
            actual.addAll(projection.passes().stream().map(AlightingSettlementProjection.Pass::event).toList());
            state = projection.snapshot();
        }
        var finalStep = new AlightingSettlementProjection(new AlightingSettlementProjection.Layout(100, STOPS), 1000, state);
        finalStep.completeThrough(START.plusSeconds(2000));
        actual.addAll(finalStep.passes().stream().map(AlightingSettlementProjection.Pass::event).toList());
        actual.sort(Comparator.comparing(AlightingAwarePassEvent::confirmedAt).thenComparingLong(x -> x.departure().observationId()));
        assertThat(actual).containsExactlyElementsOf(AlightingAwarePassEvents.resolve(rows, STOPS));
    }

    @Test
    void 같은_확정_시각의_배치가_나뉘어도_관측_ID_순서로_통과를_판정한다() {
        var a = row(1, 0, 1, "a", 4);
        var b = row(20, 20, 2, "a", 3);
        var c = row(10, 20, 2, "a", 0);
        var d = row(30, 40, 3, "a", 2);
        List<AlightingAwareObservation> rows = List.of(a, b, c, d);
        var state = AlightingSettlementProjection.Snapshot.empty();
        List<AlightingAwarePassEvent> actual = new ArrayList<>();
        for (var row : rows) {
            var p = new AlightingSettlementProjection(new AlightingSettlementProjection.Layout(100, STOPS), 100, state);
            p.append(row.receivedAt(), List.of(row), false);
            actual.addAll(p.passes().stream().map(AlightingSettlementProjection.Pass::event).toList());
            state = p.snapshot();
        }
        var p = new AlightingSettlementProjection(new AlightingSettlementProjection.Layout(100, STOPS), 100, state);
        p.completeThrough(START.plusSeconds(40));
        actual.addAll(p.passes().stream().map(AlightingSettlementProjection.Pass::event).toList());
        assertThat(actual).containsExactlyElementsOf(AlightingAwarePassEvents.resolve(rows, STOPS));
    }

    @Test
    void 나중_입력의_한도와_누락_경계를_확정_시각으로_남긴다() {
        var p = new AlightingSettlementProjection(new AlightingSettlementProjection.Layout(100, STOPS), 1,
            AlightingSettlementProjection.Snapshot.empty());
        p.append(START, List.of(row(1, 0, 1, "a", 4)), false);
        p.append(START.plusSeconds(20), List.of(row(2, 20, 2, "a", 3)), true);
        assertThat(p.snapshot().overflowAt()).isEqualTo(START.plusSeconds(20));
        assertThat(p.snapshot().incompleteAt()).isEqualTo(START.plusSeconds(20));
        assertThat(p.indexed()).hasSize(1);
    }

    @Test
    void 연결_단절과_좌석_누락을_섞어도_전체_판정과_같다() {
        var random = new java.util.Random(20261112);
        List<AlightingAwareObservation> rows = new ArrayList<>();
        int[] positions = new int[5];
        for (int i = 1; i <= 2000; i++) {
            int vehicle = random.nextInt(positions.length);
            positions[vehicle] = (positions[vehicle] + random.nextInt(3)) % 6;
            Instant at = START.plusSeconds(i / 3 * 20L);
            rows.add(new AlightingAwareObservation(3000 - i, i, 0, at, "v" + vehicle,
                positions[vehicle], positions[vehicle], 2, random.nextInt(5) == 0 ? null : random.nextInt(41),
                0, 1, at.minusSeconds(random.nextInt(91)), false));
        }
        var state = AlightingSettlementProjection.Snapshot.empty();
        List<AlightingAwarePassEvent> actual = new ArrayList<>();
        for (var row : rows) {
            var projection = new AlightingSettlementProjection(new AlightingSettlementProjection.Layout(100, STOPS), 10000, state);
            projection.append(row.receivedAt(), List.of(row), false);
            actual.addAll(projection.passes().stream().map(AlightingSettlementProjection.Pass::event).toList());
            state = projection.snapshot();
        }
        var last = new AlightingSettlementProjection(new AlightingSettlementProjection.Layout(100, STOPS), 10000, state);
        last.completeThrough(rows.getLast().receivedAt());
        actual.addAll(last.passes().stream().map(AlightingSettlementProjection.Pass::event).toList());
        actual.sort(Comparator.comparing(AlightingAwarePassEvent::confirmedAt).thenComparingLong(event -> event.departure().observationId()));
        assertThat(actual).containsExactlyElementsOf(AlightingAwarePassEvents.resolve(rows, STOPS));
    }

    private static AlightingAwareObservation row(long id, int second, int stop, String vehicle, Integer seats) {
        return new AlightingAwareObservation(id, id, 0, START.plusSeconds(second), vehicle, stop, stop, 2,
            seats, 0, 1, START.plusSeconds(second), false);
    }
}
