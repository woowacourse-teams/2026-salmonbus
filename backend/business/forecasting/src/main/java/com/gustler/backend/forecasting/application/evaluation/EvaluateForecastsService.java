package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.diagnostics.WorkerOperationLog;
import com.gustler.backend.forecasting.api.evaluation.EvaluateForecasts;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 새 모델의 정산만 실행한다. 노선별 실패 격리는 정산 저장소가 담당한다. */
@Component
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class EvaluateForecastsService implements EvaluateForecasts {
    private final AlightingAwareForecastEvaluation evaluation;

    public EvaluateForecastsService(AlightingAwareForecastEvaluation evaluation) {
        this.evaluation = evaluation;
    }

    @Override
    public void settleArrivalLabels() {
        WorkerOperationLog.run("alighting_settlement_tick", "all", evaluation::settle);
    }
}
