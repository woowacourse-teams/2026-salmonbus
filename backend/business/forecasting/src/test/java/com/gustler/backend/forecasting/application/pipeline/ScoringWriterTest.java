package com.gustler.backend.forecasting.application.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        assertThat(flushed).isEqualTo(new ScoringWriter.Flushed(2, 1));
        assertThat(transactions.calls.get()).isOne();
        assertThat(store.labels).extracting(PassLabel::tripStartObservationId).containsExactly(1L, 2L);
        assertThat(writer.pendingCount()).isZero();
    }

    @Test
    void 쓸_줄이_없으면_트랜잭션을_열지_않는다() {
        // given
        CountingTransactions transactions = new CountingTransactions();
        ScoringWriter writer = new ScoringWriter(new MemoryStore(), transactions);

        // when
        ScoringWriter.Flushed flushed = writer.flush();

        // then
        assertThat(flushed).isEqualTo(new ScoringWriter.Flushed(0, 0));
        assertThat(transactions.calls.get()).isZero();
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
        assertThat(flushed.labels()).isEqualTo(2);
        assertThat(store.labels).extracting(PassLabel::tripStartObservationId).containsExactly(1L, 2L);
    }

    private static PassLabel label(long trip) {
        return new PassLabel(DAY, 7, trip, 3, 40, ScoringReasons.LABEL_VERSION, 3, PassLabel.State.SETTLED, null,
            12.5, null, null, null, AT);
    }

    private static UnresolvedForecast unresolved(long observation) {
        return new UnresolvedForecast(DAY, observation, 3, 7, UnresolvedForecast.State.LOST,
            ScoringReasons.PASS_NOT_CLOSED, AT);
    }

    private static final class MemoryStore implements PassLabelStore {
        private final List<PassLabel> labels = new ArrayList<>();
        private final List<UnresolvedForecast> unresolved = new ArrayList<>();
        private int failures;

        @Override
        public int saveLabels(List<PassLabel> saved) {
            if (failures-- > 0) {
                throw new IllegalStateException("쓰기 실패");
            }
            labels.addAll(saved);
            return saved.size();
        }

        @Override
        public int saveUnresolved(List<UnresolvedForecast> saved) {
            unresolved.addAll(saved);
            return saved.size();
        }
    }

    private static final class CountingTransactions implements TransactionOperations {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            calls.incrementAndGet();
            return action.doInTransaction(null);
        }
    }
}
