package com.gustler.backend.forecasting.application.forecast;

import java.util.List;

/**
 * 정산 결과다. 정답과 미해결 기록은 저장하고, 완료 결과는 새로 저장한 행의 이벤트를 알리는 데 사용한다.
 * 완료 결과는 아래 저장 목록에 든 객체를 그대로 참조한다.
 *
 * @param labels 새 통과 정답
 * @param unresolved 정답 없이 종료한 예측
 * @param closed 예측별 완료 결과와 저장할 행의 연결
 */
public record ScoringResult(
    List<PassLabel> labels,
    List<UnresolvedForecast> unresolved,
    List<ClosedForecast> closed
) {

    private static final ScoringResult EMPTY = new ScoringResult(List.of(), List.of(), List.of());

    public ScoringResult {
        labels = List.copyOf(labels);
        unresolved = List.copyOf(unresolved);
        closed = List.copyOf(closed);
    }

    public static ScoringResult empty() {
        return EMPTY;
    }

    /** 저장할 행이 없으면 참이다. */
    public boolean isEmpty() {
        return labels.isEmpty() && unresolved.isEmpty();
    }

    /** 두 결과를 합친다. 반복 처리의 결과는 combine으로 한 번에 모은다. */
    public ScoringResult plus(ScoringResult other) {
        if (other.carriesNothing()) {
            return this;
        }
        return combine(List.of(this, other));
    }

    /** 입력 순서대로 목록을 모으며 저장할 행의 객체 참조는 유지한다. */
    public static ScoringResult combine(List<ScoringResult> results) {
        if (results.isEmpty()) {
            return EMPTY;
        }
        return new ScoringResult(
                results.stream()
                    .flatMap(result -> result.labels().stream())
                    .toList(),
                results.stream()
                    .flatMap(result -> result.unresolved().stream())
                    .toList(),
                results.stream()
                    .flatMap(result -> result.closed().stream())
                    .toList()
        );
    }

    private boolean carriesNothing() {
        return labels.isEmpty() && unresolved.isEmpty() && closed.isEmpty();
    }
}
