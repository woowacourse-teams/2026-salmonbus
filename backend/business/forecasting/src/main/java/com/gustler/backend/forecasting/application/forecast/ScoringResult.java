package com.gustler.backend.forecasting.application.forecast;

import java.util.ArrayList;
import java.util.List;

/**
 * 정산 단계가 이벤트 하나를 처리해 낸 결과다. 저장은 호출자가 한다.
 *
 * @param labels 새 통과 정답
 * @param unresolved 정답을 받지 못하고 정산을 마친 예측
 * @param scored 정답에 짝지어진 예측. 저장하지 않는다
 * @param closed 정산을 마친 예측과 저장할 행을 같이 반환한다. 직접 저장하지 않는다. 행은 {@code labels}나 {@code unresolved}에 든
 *     객체다
 */
public record ScoringResult(
    List<PassLabel> labels,
    List<UnresolvedForecast> unresolved,
    List<ScoredForecast> scored,
    List<ClosedForecast> closed
) {

    private static final ScoringResult EMPTY = new ScoringResult(
            List.of(),
            List.of(),
            List.of(),
            List.of()
    );

    public ScoringResult {
        labels = List.copyOf(labels);
        unresolved = List.copyOf(unresolved);
        scored = List.copyOf(scored);
        closed = List.copyOf(closed);
    }

    /** 빈 결과를 반환한다. */
    public static ScoringResult empty() {
        return EMPTY;
    }

    /** 저장할 줄이 없으면 참이다. */
    public boolean isEmpty() {
        return labels.isEmpty() && unresolved.isEmpty();
    }

    /**
     * 두 결과를 순서대로 이어 붙인다.
     *
     * @param other 뒤에 붙일 결과
     * @return 합친 결과
     */
    public ScoringResult plus(ScoringResult other) {
        if (other.carriesNothing()) {
            return this;
        }
        List<PassLabel> mergedLabels = new ArrayList<>(labels);
        mergedLabels.addAll(other.labels);
        List<UnresolvedForecast> mergedUnresolved = new ArrayList<>(unresolved);
        mergedUnresolved.addAll(other.unresolved);
        List<ScoredForecast> mergedScored = new ArrayList<>(scored);
        mergedScored.addAll(other.scored);
        List<ClosedForecast> mergedClosed = new ArrayList<>(closed);
        mergedClosed.addAll(other.closed);
        return new ScoringResult(
                mergedLabels,
                mergedUnresolved,
                mergedScored,
                mergedClosed
        );
    }

    private boolean carriesNothing() {
        return labels.isEmpty() && unresolved.isEmpty() && scored.isEmpty() && closed.isEmpty();
    }
}
