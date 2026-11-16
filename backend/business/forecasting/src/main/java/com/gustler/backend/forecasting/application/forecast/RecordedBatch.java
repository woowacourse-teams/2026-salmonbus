package com.gustler.backend.forecasting.application.forecast;

import java.time.Instant;
import java.util.Objects;

/**
 * 예보 실행 줄({@code forecast_run})에 함께 적어 둔 배치의 수집 사실이다. 채점 되감기가 수집 사건 없이 그날의 수집 구멍과 관측 줄
 * 수를 되살리는 데 쓴다({@link ScoringStage#restore}).
 *
 * @param observationBatchId 관측 배치
 * @param routeId 노선
 * @param receivedAt 배치 응답 수신 시각
 * @param confirmedAt 예보 착수 시각. 수집 구멍과 관측 줄 수를 기록하는 시각이다
 * @param late 늦게 착수해 예보하지 않은 배치면 참. 채점 단계는 이런 배치의 구멍과 줄 수를 기록하지 않는다
 * @param incomplete 저장 줄 수와 실제로 남은 줄 수가 다른 성공 배치(수집 구멍)면 참
 * @param countedRows 하루 관측 상한 계산에 더할 줄 수. 0 이상이다
 */
public record RecordedBatch(
    long observationBatchId,
    long routeId,
    Instant receivedAt,
    Instant confirmedAt,
    boolean late,
    boolean incomplete,
    int countedRows
) {
    public RecordedBatch {
        Objects.requireNonNull(receivedAt, "수신 시각이 필요하다");
        Objects.requireNonNull(confirmedAt, "착수 시각이 필요하다");
        if (countedRows < 0) {
            throw new IllegalArgumentException("줄 수는 0 이상이어야 한다: " + countedRows);
        }
    }
}
