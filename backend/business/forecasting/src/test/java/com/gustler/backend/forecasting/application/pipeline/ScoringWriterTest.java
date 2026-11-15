package com.gustler.backend.forecasting.application.pipeline;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

class ScoringWriterTest {
    private static final LocalDate DAY = LocalDate.of(2026, 1, 5);
    private static final Instant AT = Instant.parse("2026-01-05T00:00:00Z");

    @Test
    void 모은_정답과_미해결을_한_트랜잭션으로_쓴다() {
        // given
        MemoryStore store = new MemoryStore();
        CountingTransactions transactions = new CountingTransactions();
        ScoringWriter writer = new ScoringWriter(store, transactions);
        writer.add(new ScoringResult(List.of(label(1)), List.of(unresolved(10)), List.of()));
        writer.add(new ScoringResult(List.of(label(2)), List.of(), List.of()));

        // when
        ScoringWriter.Flushed flushed = writer.flush();

        // then
        assertSoftly(softly -> {
            softly.assertThat(flushed).isEqualTo(new ScoringWriter.Flushed(2, 1));
            softly.assertThat(transactions.calls.get()).isOne();
            softly.assertThat(store.labels).extracting(PassLabel::tripStartObservationId).containsExactly(1L, 2L);
            softly.assertThat(writer.pendingCount()).isZero();
        });
    }

    @Test
    void 쓸_줄이_없으면_트랜잭션을_열지_않는다() {
        // given
        CountingTransactions transactions = new CountingTransactions();
        ScoringWriter writer = new ScoringWriter(new MemoryStore(), transactions);

        // when
        ScoringWriter.Flushed flushed = writer.flush();

        // then
        assertSoftly(softly -> {
            softly.assertThat(flushed).isEqualTo(new ScoringWriter.Flushed(0, 0));
            softly.assertThat(transactions.calls.get()).isZero();
        });
    }

    @Test
    void 쓰기가_실패하면_꺼낸_줄을_앞에_돌려놓고_다음_쓰기에서_다시_쓴다() {
        // given
        MemoryStore store = new MemoryStore();
        store.failures = 1;
        ScoringWriter writer = new ScoringWriter(store, new CountingTransactions());
        writer.add(new ScoringResult(List.of(label(1)), List.of(), List.of()));
        assertThatThrownBy(writer::flush).isInstanceOf(IllegalStateException.class);
        writer.add(new ScoringResult(List.of(label(2)), List.of(), List.of()));

        // when
        ScoringWriter.Flushed flushed = writer.flush();

        // then
        assertSoftly(softly -> {
            softly.assertThat(flushed.labels()).isEqualTo(2);
            softly.assertThat(store.labels).extracting(PassLabel::tripStartObservationId).containsExactly(1L, 2L);
        });
    }

    @Test
    void 기다리는_줄이_상한보다_많으면_넣은_순서대로_결과_단위의_경계에서_여러_트랜잭션에_나눠_쓴다() {
        // given
        MemoryStore store = new MemoryStore();
        CountingTransactions transactions = new CountingTransactions(store);
        ScoringWriter writer = new ScoringWriter(store, transactions, 3);
        writer.add(new ScoringResult(List.of(label(1)), List.of(unresolved(10)), List.of()));
        writer.add(new ScoringResult(List.of(label(2)), List.of(), List.of()));
        writer.add(new ScoringResult(List.of(label(3)), List.of(unresolved(11)), List.of()));
        writer.add(new ScoringResult(List.of(label(4), label(5)), List.of(unresolved(12), unresolved(13)), List.of()));

        // when
        ScoringWriter.Flushed flushed = writer.flush();

        // then
        assertSoftly(softly -> {
            softly.assertThat(flushed).isEqualTo(new ScoringWriter.Flushed(5, 4));
            softly.assertThat(store.writes).containsExactly(List.of(1L, 2L, 10L), List.of(3L, 11L),
                List.of(4L, 5L, 12L, 13L));
            softly.assertThat(transactions.calls.get()).isEqualTo(3);
            softly.assertThat(writer.pendingCount()).isZero();
        });
    }

    @Test
    void 나눠_쓰다_실패하면_먼저_넣은_결과만_저장된_채로_남고_나머지는_순서대로_다시_쓴다() {
        // given
        MemoryStore store = new MemoryStore();
        ScoringWriter writer = new ScoringWriter(store, new CountingTransactions(store), 2);
        writer.add(new ScoringResult(List.of(), List.of(unresolved(10), unresolved(11)), List.of()));
        writer.add(new ScoringResult(List.of(label(1)), List.of(unresolved(12)), List.of()));
        writer.add(new ScoringResult(List.of(label(2)), List.of(unresolved(13)), List.of()));
        store.failures = 1;

        // when
        Throwable failure = catchThrowable(writer::flush);
        List<List<Long>> writesAfterFailure = List.copyOf(store.writes);
        final int pendingAfterFailure = writer.pendingCount();
        writer.flush();

        // then
        assertSoftly(softly -> {
            softly.assertThat(failure).isInstanceOf(IllegalStateException.class);
            softly.assertThat(writesAfterFailure).containsExactly(List.of(10L, 11L));
            softly.assertThat(pendingAfterFailure).isEqualTo(4);
            softly.assertThat(store.writes).containsExactly(List.of(10L, 11L), List.of(1L, 12L), List.of(2L, 13L));
        });
    }

    private static PassLabel label(final long trip) {
        return new PassLabel(DAY, 7, trip, 3, 40, ScoringReasons.LABEL_VERSION, 3, PassLabel.State.SETTLED, null,
            12.5, null, null, null, AT);
    }

    private static UnresolvedForecast unresolved(final long observation) {
        return new UnresolvedForecast(DAY, observation, 3, 7, UnresolvedForecast.State.LOST,
            ScoringReasons.PASS_NOT_CLOSED, AT);
    }

    /** 정답 줄 쓰기가 실패하면 그 트랜잭션에서는 아무것도 남기지 않는다. 트랜잭션마다 쓴 줄의 번호를 정답, 미해결 순서로 적어 둔다. */
    private static final class MemoryStore implements PassLabelStore {
        private final List<PassLabel> labels = new ArrayList<>();
        private final List<UnresolvedForecast> unresolved = new ArrayList<>();
        private final List<List<Long>> writes = new ArrayList<>();
        private List<Long> current = new ArrayList<>();
        private int failures;

        @Override
        public int saveLabels(List<PassLabel> saved) {
            if (failures-- > 0) {
                throw new IllegalStateException("쓰기 실패");
            }
            labels.addAll(saved);
            saved.forEach(label -> current.add(label.tripStartObservationId()));
            return saved.size();
        }

        @Override
        public int saveUnresolved(List<UnresolvedForecast> saved) {
            unresolved.addAll(saved);
            saved.forEach(closed -> current.add(closed.vehicleObservationId()));
            return saved.size();
        }

        private void committed() {
            writes.add(current);
            current = new ArrayList<>();
        }
    }

    private static final class CountingTransactions implements TransactionOperations {
        private final AtomicInteger calls = new AtomicInteger();
        private final MemoryStore store;

        private CountingTransactions() {
            this(null);
        }

        private CountingTransactions(MemoryStore store) {
            this.store = store;
        }

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            calls.incrementAndGet();
            T result = action.doInTransaction(null);
            if (store != null) {
                store.committed();
            }
            return result;
        }
    }
}
