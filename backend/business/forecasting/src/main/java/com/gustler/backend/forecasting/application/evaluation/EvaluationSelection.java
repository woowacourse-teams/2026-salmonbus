package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.forecasting.application.evaluation.EvaluationWorkPageReader.Page;
import com.gustler.backend.forecasting.application.evaluation.EvaluationWorkPageReader.Ready;
import com.gustler.backend.forecasting.domain.evaluation.PendingForecast;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 관측·대상 방문 순서와 부분 관측 커서를 유지하며 한 회차의 평가 대상을 제한한다.
 */
final class EvaluationSelection {
    record Cursor(long through, long after, long partialObservation, int afterTarget) {
        static final Cursor START = new Cursor(0, 0, 0, 0);
    }

    record Selected(Cursor next, List<PendingForecast> forecasts) {
        Selected {
            forecasts = List.copyOf(forecasts);
        }
    }

    static Selected take(Cursor cursor, Page page, final int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("평가 한도는 양수여야 한다");
        }
        if (page.checkedIds().isEmpty()) {
            return new Selected(Cursor.START, List.of());
        }
        Map<Long, Ready> ready = page.ready()
            .stream()
            .collect(Collectors.toMap(Ready::observationId, Function.identity()));
        List<PendingForecast> selected = new ArrayList<>();
        Cursor next = cursor;
        for (final long id : page.checkedIds()) {
            if (selected.size() == limit) {
                break;
            }
            Ready input = ready.get(id);
            List<PendingForecast> remaining = remainingForecasts(cursor, input, id);
            final int count = Math.min(limit - selected.size(), remaining.size());
            selected.addAll(remaining.subList(0, count));
            if (count < remaining.size()) {
                next = new Cursor(cursor.through(), next.after(), id,
                    remaining.get(count - 1).targetStopOrder());
                break;
            }
            next = new Cursor(cursor.through(), id, 0, 0);
        }
        if (next.partialObservation() == 0 && next.after() >= next.through()) {
            next = Cursor.START;
        }
        return new Selected(next, selected);
    }

    private static List<PendingForecast> remainingForecasts(Cursor cursor, Ready input, final long id) {
        if (input == null) {
            return List.of();
        }
        return input.forecasts()
            .stream()
            .filter(forecast -> id != cursor.partialObservation()
                || forecast.targetStopOrder() > cursor.afterTarget())
            .sorted(Comparator.comparingInt(PendingForecast::targetStopOrder))
            .toList();
    }
}
