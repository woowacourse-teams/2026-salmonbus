package com.gustler.backend.forecasting.application.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.diagnostics.WorkerOperationLog;
import com.gustler.backend.forecasting.api.forecast.ForecastClosed;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
        writer.add(new ScoringResult(
                List.of(label(1)),
                List.of(unresolved(10)),
                List.of(),
                List.of()
        ));
        writer.add(new ScoringResult(
                List.of(label(2)),
                List.of(),
                List.of(),
                List.of()
        ));

        // when
        ScoringWriter.Flushed flushed = writer.flush();

        // then
        assertSoftly(softly -> {
            softly.assertThat(flushed).isEqualTo(new ScoringWriter.Flushed(2, 1, List.of()));
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
            softly.assertThat(flushed).isEqualTo(new ScoringWriter.Flushed(0, 0, List.of()));
            softly.assertThat(transactions.calls.get()).isZero();
        });
    }

    @Test
    void 쓰기가_실패하면_꺼낸_줄을_앞에_돌려놓고_다음_쓰기에서_다시_쓴다() {
        // given
        MemoryStore store = new MemoryStore();
        store.failures = 1;
        ScoringWriter writer = new ScoringWriter(store, new CountingTransactions());
        writer.add(new ScoringResult(
                List.of(label(1)),
                List.of(),
                List.of(),
                List.of()
        ));
        assertThatThrownBy(writer::flush).isInstanceOf(IllegalStateException.class);
        writer.add(new ScoringResult(
                List.of(label(2)),
                List.of(),
                List.of(),
                List.of()
        ));

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
        writer.add(new ScoringResult(
                List.of(label(1)),
                List.of(unresolved(10)),
                List.of(),
                List.of()
        ));
        writer.add(new ScoringResult(
                List.of(label(2)),
                List.of(),
                List.of(),
                List.of()
        ));
        writer.add(new ScoringResult(
                List.of(label(3)),
                List.of(unresolved(11)),
                List.of(),
                List.of()
        ));
        writer.add(new ScoringResult(
                List.of(label(4), label(5)),
                List.of(unresolved(12), unresolved(13)),
                List.of(),
                List.of()
        ));

        // when
        ScoringWriter.Flushed flushed = writer.flush();

        // then
        assertSoftly(softly -> {
            softly.assertThat(flushed).isEqualTo(new ScoringWriter.Flushed(5, 4, List.of()));
            softly.assertThat(store.writes).containsExactly(List.of(1L, 2L, 10L), List.of(3L, 11L),
                List.of(
                        4L,
                        5L,
                        12L,
                        13L
                ));
            softly.assertThat(transactions.calls.get()).isEqualTo(3);
            softly.assertThat(writer.pendingCount()).isZero();
        });
    }

    @Test
    void 나눠_쓰다_실패하면_먼저_넣은_결과만_저장된_채로_남고_나머지는_순서대로_다시_쓴다() {
        // given
        MemoryStore store = new MemoryStore();
        ScoringWriter writer = new ScoringWriter(store, new CountingTransactions(store), 2);
        writer.add(new ScoringResult(
                List.of(),
                List.of(unresolved(10), unresolved(11)),
                List.of(),
                List.of()
        ));
        writer.add(new ScoringResult(
                List.of(label(1)),
                List.of(unresolved(12)),
                List.of(),
                List.of()
        ));
        writer.add(new ScoringResult(
                List.of(label(2)),
                List.of(unresolved(13)),
                List.of(),
                List.of()
        ));
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

    @Test
    void 닫힌_예측은_그_결과_줄이_새로_들어간_쓰기에서만_돌려준다() {
        // given
        MemoryStore store = new MemoryStore();
        ScoringWriter writer = new ScoringWriter(store, new CountingTransactions());
        PassLabel stored = label(1);
        UnresolvedForecast storedLost = unresolved(10);
        store.saveLabels(List.of(stored));
        store.saveUnresolved(List.of(storedLost));
        PassLabel fresh = label(2);
        UnresolvedForecast freshLost = unresolved(11);
        writer.add(new ScoringResult(
                List.of(label(1), fresh),
                List.of(unresolved(10), freshLost),
                List.of(),
                List.of(
                    ClosedForecast.scored(fresh, event(21)), ClosedForecast.scored(fresh, event(22)),
                    ClosedForecast.unresolved(freshLost, event(23)))
        ));

        // when
        ScoringWriter.Flushed flushed = writer.flush();

        // then
        assertSoftly(softly -> {
            softly.assertThat(flushed.labels()).isOne();
            softly.assertThat(flushed.unresolved()).isOne();
            softly.assertThat(flushed.closed()).containsExactly(event(21), event(22), event(23));
        });
    }

    @Test
    void 되감기가_이미_저장된_줄을_다시_넣으면_그_줄로_닫힌_예측은_돌려주지_않는다() {
        // given
        MemoryStore store = new MemoryStore();
        ScoringWriter writer = new ScoringWriter(store, new CountingTransactions());
        writer.add(closing(label(1), unresolved(10)));
        ScoringWriter.Flushed first = writer.flush();
        writer.add(closing(label(1), unresolved(10)));

        // when
        ScoringWriter.Flushed replayed = writer.flush();

        // then
        assertSoftly(softly -> {
            softly.assertThat(first.closed()).containsExactly(event(1), event(10));
            softly.assertThat(replayed).isEqualTo(new ScoringWriter.Flushed(0, 0, List.of()));
        });
    }

    @Test
    void 같은_줄이_쓰기_대기에_둘_있으면_먼저_넣은_쪽의_예측만_돌려준다() {
        // given
        ScoringWriter writer = new ScoringWriter(
            new MemoryStore(),
            new CountingTransactions()
        );
        writer.add(closing(label(1), unresolved(10)));
        writer.add(closing(label(1), unresolved(10)));

        // when
        ScoringWriter.Flushed flushed = writer.flush();

        // then
        assertThat(flushed).isEqualTo(new ScoringWriter.Flushed(1, 1, List.of(event(1), event(10))));
    }

    @Test
    void 쓰기에_실패한_결과의_닫힌_예측은_다시_써서_성공한_때에_한_번_돌려준다() {
        // given
        MemoryStore store = new MemoryStore();
        store.failures = 1;
        ScoringWriter writer = new ScoringWriter(store, new CountingTransactions());
        writer.add(closing(label(1), unresolved(10)));
        Throwable failure = catchThrowable(writer::flush);

        // when
        ScoringWriter.Flushed flushed = writer.flush();

        // then
        assertSoftly(softly -> {
            softly.assertThat(failure).isInstanceOf(IllegalStateException.class);
            softly.assertThat(flushed.closed()).containsExactly(event(1), event(10));
        });
    }

    @Test
    void 쓰기_트랜잭션마다_걸린_시간을_채점_쓰기_작업으로_남기고_쓸_줄이_없으면_남기지_않는다() {
        // given
        List<String> operations = new ArrayList<>();
        WorkerOperationLog.Listener listener = (operation, route, nanos, failed) -> operations
            .add(operation + ":" + route + ":" + failed);
        MemoryStore store = new MemoryStore();
        ScoringWriter writer = new ScoringWriter(store, new CountingTransactions(store), 1);
        WorkerOperationLog.setListener(listener);
        try {
            writer.flush();
            writer.add(closing(label(1), unresolved(10)));
            writer.add(closing(label(2), unresolved(11)));
            store.failures = 1;

            // when
            Throwable failure = catchThrowable(writer::flush);
            writer.flush();

            // then
            assertSoftly(softly -> {
                softly.assertThat(failure).isInstanceOf(IllegalStateException.class);
                softly.assertThat(operations).containsExactly("scoring_write:all:true", "scoring_write:all:false",
                    "scoring_write:all:false");
            });
        } finally {
            WorkerOperationLog.clearListener(listener);
        }
    }

    /** 정답 줄 하나와 미해결 줄 하나, 그 줄로 닫힌 예측을 하나씩 담은 결과다. 사건의 목표 정류장에 줄의 번호를 적는다. */
    private static ScoringResult closing(PassLabel label, UnresolvedForecast lost) {
        return new ScoringResult(
                List.of(label),
                List.of(lost),
                List.of(),
                List.of(
                    ClosedForecast.scored(label, event((int) label.tripStartObservationId())),
                    ClosedForecast.unresolved(lost, event((int) lost.vehicleObservationId())))
        );
    }

    private static ForecastClosed event(final int targetStopOrder) {
        return new ForecastClosed(
                7,
                70,
                3,
                targetStopOrder,
                2,
                11.5,
                0.25,
                null,
                null
        );
    }

    private static PassLabel label(final long trip) {
        return new PassLabel(
                DAY,
                7,
                trip,
                3,
                40,
                ScoringReasons.LABEL_VERSION,
                3,
                PassLabel.State.SETTLED,
                null,
                12.5,
                null,
                null,
                null,
                AT
        );
    }

    private static UnresolvedForecast unresolved(final long observation) {
        return new UnresolvedForecast(
                DAY,
                observation,
                3,
                7,
                UnresolvedForecast.State.LOST,
                ScoringReasons.PASS_NOT_CLOSED,
                AT
        );
    }

    /**
     * 정답 줄 쓰기가 실패하면 그 트랜잭션에서는 아무것도 남기지 않는다. 트랜잭션마다 쓴 줄의 번호를 정답, 미해결 순서로 적어 둔다.
     * 저장소처럼 이미 있는 번호의 줄은 넣지 않고, 새로 넣은 줄만 받은 객체 그대로 돌려준다.
     */
    private static final class MemoryStore implements PassLabelStore {

        private final List<PassLabel> labels = new ArrayList<>();
        private final List<UnresolvedForecast> unresolved = new ArrayList<>();
        private final List<List<Long>> writes = new ArrayList<>();
        private final Set<Long> labelKeys = new HashSet<>();
        private final Set<Long> unresolvedKeys = new HashSet<>();
        private List<Long> current = new ArrayList<>();
        private int failures;

        @Override
        public List<PassLabel> saveLabels(List<PassLabel> saved) {
            if (failures-- > 0) {
                throw new IllegalStateException("쓰기 실패");
            }
            List<PassLabel> inserted = saved.stream()
                .filter(label -> labelKeys.add(label.tripStartObservationId()))
                .toList();
            labels.addAll(inserted);
            inserted.forEach(label -> current.add(label.tripStartObservationId()));
            return inserted;
        }

        @Override
        public List<UnresolvedForecast> saveUnresolved(List<UnresolvedForecast> saved) {
            List<UnresolvedForecast> inserted = saved.stream()
                .filter(closed -> unresolvedKeys.add(closed.vehicleObservationId()))
                .toList();
            unresolved.addAll(inserted);
            inserted.forEach(closed -> current.add(closed.vehicleObservationId()));
            return inserted;
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
