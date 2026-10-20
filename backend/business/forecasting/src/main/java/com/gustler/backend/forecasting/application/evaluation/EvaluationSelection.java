package com.gustler.backend.forecasting.application.evaluation;
import com.gustler.backend.forecasting.domain.evaluation.PendingForecast;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

final class EvaluationSelection {
    record Cursor(long through, long after, long partialObservation, int afterTarget) {
        static final Cursor START = new Cursor(0, 0, 0, 0);
    }

    record Selected(Cursor next, List<PendingForecast> forecasts) {
        Selected {
            forecasts = List.copyOf(forecasts);
        }
    }

    static Selected take(Cursor cursor, EvaluationWorkPageReader.Page page, int limit) {
        if (limit < 1) throw new IllegalArgumentException("평가 한도는 양수여야 한다");
        if (page.checkedIds().isEmpty()) return new Selected(Cursor.START, List.of());
        Map<Long, EvaluationWorkPageReader.Ready> ready = page.ready()
            .stream()
            .collect(Collectors.toMap(EvaluationWorkPageReader.Ready::observationId, Function.identity()));
        List<PendingForecast> selected = new ArrayList<>();
        Cursor next = cursor;
        for (long id : page.checkedIds()) {
            if (selected.size() == limit) break;
            var input = ready.get(id);
            List<PendingForecast> remaining = input == null ? List.of() : input.forecasts().stream()
                .filter(forecast -> id != cursor.partialObservation()
                    || forecast.targetStopOrder() > cursor.afterTarget())
                .sorted(Comparator.comparingInt(PendingForecast::targetStopOrder)).toList();
            int count = Math.min(limit - selected.size(), remaining.size());
            selected.addAll(remaining.subList(0, count));
            if (count < remaining.size()) {
                next = new Cursor(cursor.through(), next.after(), id,
                    remaining.get(count - 1).targetStopOrder());
                break;
            }
            next = new Cursor(cursor.through(), id, 0, 0);
        }
        if (next.partialObservation() == 0 && next.after() >= next.through()) next = Cursor.START;
        return new Selected(next, selected);
    }
}
