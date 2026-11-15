package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.api.forecast.ForecastOutcome;
import com.gustler.backend.forecasting.api.forecast.ForecastPublished;
import com.gustler.backend.forecasting.api.forecast.ObservationBatchSeen;
import com.gustler.backend.forecasting.api.forecast.PassClosed;
import com.gustler.backend.forecasting.application.forecast.ForecastRecordStore;
import com.gustler.backend.forecasting.application.forecast.PassLabel;
import com.gustler.backend.forecasting.application.forecast.RecordedBatch;
import com.gustler.backend.forecasting.application.forecast.ScoringResult;
import com.gustler.backend.forecasting.application.forecast.StopPassStore;
import com.gustler.backend.forecasting.application.forecast.UnresolvedForecast;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 저장된 사건 목록을 채점 되감기가 읽는 저장소처럼 보여 주는 시험용 저장소다. 예보 경로가 배치마다 실행 줄(수집 사실 포함)과 통과를
 * 저장한 뒤에만 사건을 넘기는 것을 흉내 내도록, 목록에 든 수집·통과·예보 사건을 그대로 읽는다. 목록은 시험이 늘려 가도 된다.
 */
final class RecordedScoringEvents {
    private final List<Object> saved;
    private final Supplier<Collection<Object>> rows;
    private final Runnable beforeForecastRead;

    /**
     * @param saved 저장된 사건. 배치마다 수집, 통과, 예보 순서다
     * @param rows 저장된 정답 줄({@link PassLabel})과 미해결 줄({@link UnresolvedForecast}). 채점이 저장까지 마친 사건 시각을 찾는 데
     *     쓴다
     */
    RecordedScoringEvents(List<Object> saved, Supplier<Collection<Object>> rows) {
        this(saved, rows, () -> {
        });
    }

    /**
     * @param saved 저장된 사건. 배치마다 수집, 통과, 예보 순서다
     * @param rows 저장된 정답 줄과 미해결 줄
     * @param beforeForecastRead 예보 기록을 읽기 직전에 부른다. 읽기 실패를 흉내 내려면 여기서 예외를 던진다
     */
    RecordedScoringEvents(
        List<Object> saved,
        Supplier<Collection<Object>> rows,
        Runnable beforeForecastRead
    ) {
        this.saved = saved;
        this.rows = rows;
        this.beforeForecastRead = beforeForecastRead;
    }

    /** 채점 결과에 담긴 정답 줄과 미해결 줄을 저장된 줄로 본다. */
    static Collection<Object> rowsOf(ScoringResult result) {
        List<Object> rows = new ArrayList<>(result.labels());
        rows.addAll(result.unresolved());
        return rows;
    }

    /** 예보 실행 줄을 읽는 저장소. 수집 사실은 같은 배치의 저장된 수집 사건으로 만든다. */
    ForecastRecordStore forecasts() {
        return new Forecasts();
    }

    /** 통과를 읽는 저장소. */
    StopPassStore passes() {
        return new Passes();
    }

    private static boolean isBetween(Instant at, Instant fromInclusive, Instant toExclusive) {
        return !at.isBefore(fromInclusive) && at.isBefore(toExclusive);
    }

    private final class Forecasts implements ForecastRecordStore {
        @Override
        public boolean save(ForecastPublished published, ObservationBatchSeen seen) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ForecastPublished> findPublishedBetween(
            final long routeId,
            Instant fromInclusive,
            Instant toExclusive
        ) {
            beforeForecastRead.run();
            return published(routeId, fromInclusive, toExclusive);
        }

        private List<ForecastPublished> published(final long routeId, Instant fromInclusive, Instant toExclusive) {
            return saved.stream()
                .filter(ForecastPublished.class::isInstance)
                .map(ForecastPublished.class::cast)
                .filter(event -> event.routeId() == routeId)
                .filter(event -> isBetween(event.receivedAt(), fromInclusive, toExclusive))
                .toList();
        }

        @Override
        public List<RecordedBatch> findRecordedBatchesBetween(
            final long routeId,
            Instant fromInclusive,
            Instant toExclusive
        ) {
            Map<Long, ObservationBatchSeen> seenByBatch = new HashMap<>();
            for (Object event : saved) {
                if (event instanceof ObservationBatchSeen seen) {
                    seenByBatch.put(seen.observationBatchId(), seen);
                }
            }
            List<RecordedBatch> batches = new ArrayList<>();
            for (ForecastPublished published : published(routeId, fromInclusive, toExclusive)) {
                ObservationBatchSeen seen = seenByBatch.get(published.observationBatchId());
                if (seen == null) {
                    throw new IllegalStateException("수집 사건 없이 저장된 예보 사건이다: "
                        + published.observationBatchId());
                }
                batches.add(new RecordedBatch(published.observationBatchId(), routeId, published.receivedAt(),
                    published.confirmedAt(), published.outcome() == ForecastOutcome.LATE, seen.incomplete(),
                    seen.countedRows()));
            }
            return batches;
        }

        @Override
        public Optional<Instant> findLastUnresolvedConfirmedAt(
            final long routeId,
            Instant fromInclusive,
            Instant toExclusive
        ) {
            Set<Long> unresolved = new HashSet<>();
            for (Object row : rows.get()) {
                if (row instanceof UnresolvedForecast closed) {
                    unresolved.add(closed.vehicleObservationId());
                }
            }
            return published(routeId, fromInclusive, toExclusive).stream()
                .filter(event -> event.vehicles().stream()
                    .anyMatch(vehicle -> unresolved.contains(vehicle.vehicleObservationId())))
                .max(Comparator.comparing(ForecastPublished::receivedAt))
                .map(ForecastPublished::confirmedAt);
        }
    }

    private final class Passes implements StopPassStore {
        @Override
        public int save(List<PassClosed> passes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<PassClosed> findClosedBetween(final long routeId, Instant fromInclusive, Instant toExclusive) {
            return saved.stream()
                .filter(PassClosed.class::isInstance)
                .map(PassClosed.class::cast)
                .filter(pass -> pass.routeId() == routeId)
                .filter(pass -> isBetween(pass.closedAt(), fromInclusive, toExclusive))
                .toList();
        }

        @Override
        public Optional<Instant> findLastScoredClosedAt(
            final long routeId,
            Instant fromInclusive,
            Instant toExclusive
        ) {
            Set<List<Object>> labeled = new HashSet<>();
            for (Object row : rows.get()) {
                if (row instanceof PassLabel label) {
                    labeled.add(List.of(label.sourceDate(), label.routeId(), label.tripStartObservationId(),
                        label.stopOrder()));
                }
            }
            return findClosedBetween(routeId, fromInclusive, toExclusive).stream()
                .filter(pass -> labeled.contains(List.of(pass.sourceDate(), pass.routeId(),
                    pass.tripStartObservationId(), pass.stopOrder())))
                .map(PassClosed::closedAt)
                .max(Instant::compareTo);
        }
    }
}
