package com.gustler.backend.forecasting.application.forecast;

import com.gustler.backend.forecasting.api.forecast.ForecastClosed;
import java.util.Objects;

/**
 * 채점 단계가 닫은 예측 하나와 그 결과가 담긴 저장 줄이다. 저장하지 않는다.
 *
 * <p>줄이 새로 저장됐을 때만 사건을 진단에 넘기려고 줄을 함께 든다({@link ScoringWriter}). 정답에 짝지어진 예측은 정답 줄을, 그렇지
 * 않은 예측은 자기 미해결 줄을 든다. 한 통과에 짝지어진 예측들은 같은 정답 줄 객체를 가리킨다.
 *
 * @param label 짝지어진 정답 줄. 미해결로 닫혔으면 없다
 * @param unresolved 미해결 줄. 정답에 짝지어졌으면 없다
 * @param event 줄이 새로 저장되면 넘길 사건
 */
public record ClosedForecast(PassLabel label, UnresolvedForecast unresolved, ForecastClosed event) {
    public ClosedForecast {
        Objects.requireNonNull(event, "사건이 필요하다");
        if ((label == null) == (unresolved == null)) {
            throw new IllegalArgumentException("닫힌 예측은 정답 줄과 미해결 줄 가운데 하나만 가진다");
        }
    }

    static ClosedForecast scored(PassLabel label, ForecastClosed event) {
        return new ClosedForecast(label, null, event);
    }

    static ClosedForecast unresolved(UnresolvedForecast unresolved, ForecastClosed event) {
        return new ClosedForecast(null, unresolved, event);
    }

    /** 이 예측의 결과가 담긴 저장 줄. */
    Object row() {
        if (label != null) {
            return label;
        }
        return unresolved;
    }
}
