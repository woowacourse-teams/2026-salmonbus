package com.gustler.backend.forecasting.application.pipeline;

import java.util.ArrayList;
import java.util.List;

/**
 * 채점 단계가 사건 하나를 처리해 낸 결과다. 저장은 호출하는 쪽이 한다.
 *
 * @param labels 새 통과 정답
 * @param unresolved 정답을 받지 못하고 닫힌 예측
 * @param scored 정답에 짝지어진 예측. 저장하지 않는다
 */
public record ScoringResult(List<PassLabel> labels, List<UnresolvedForecast> unresolved, List<ScoredForecast> scored) {
    private static final ScoringResult EMPTY = new ScoringResult(List.of(), List.of(), List.of());

    public ScoringResult {
        labels = List.copyOf(labels);
        unresolved = List.copyOf(unresolved);
        scored = List.copyOf(scored);
    }

    /** 빈 결과를 돌려준다. */
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
        return new ScoringResult(mergedLabels, mergedUnresolved, mergedScored);
    }

    private boolean carriesNothing() {
        return labels.isEmpty() && unresolved.isEmpty() && scored.isEmpty();
    }
}
