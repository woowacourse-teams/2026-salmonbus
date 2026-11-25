package com.gustler.backend.forecasting.application.forecast;

import com.gustler.backend.forecasting.api.forecast.ForecastClosed;
import java.util.Objects;

/**
 * 정산을 마친 예측과 저장할 결과를 묶는다. 이 객체는 직접 저장하지 않는다.
 *
 * <p>새로 저장한 결과만 정확도 집계에 알리기 위해 저장할 행과 이벤트를 같이 보관한다({@link ScoringWriter}).
 * 정답이 연결된 예측은 정답 기록을, 나머지는 미해결 기록을 참조한다.
 * 같은 통과에 연결된 예측들은 같은 정답 기록 객체를 참조한다.
 *
 * @param vehicleObservationId 예측의 기준 관측 ID
 * @param label 연결된 정답 기록. 미해결로 종료했다면 없다
 * @param unresolved 미해결 기록. 정답이 연결됐다면 없다
 * @param event 결과를 새로 저장했을 때 전달할 이벤트
 */
public record ClosedForecast(
    long vehicleObservationId,
    PassLabel label,
    UnresolvedForecast unresolved,
    ForecastClosed event
) {

    public ClosedForecast {
        Objects.requireNonNull(event, "사건이 필요하다");
        if ((label == null) == (unresolved == null)) {
            throw new IllegalArgumentException("종료된 예측에는 정답 기록과 미해결 기록 중 하나만 있어야 한다");
        }
    }

    static ClosedForecast scored(final long observationId, PassLabel label, ForecastClosed event) {
        return new ClosedForecast(
                observationId,
                label,
                null,
                event
        );
    }

    static ClosedForecast unresolved(UnresolvedForecast unresolved, ForecastClosed event) {
        return new ClosedForecast(
                unresolved.vehicleObservationId(),
                null,
                unresolved,
                event
        );
    }

    /** 이 예측의 정산 결과로 저장할 행이다. */
    Object row() {
        if (label != null) {
            return label;
        }
        return unresolved;
    }
}
