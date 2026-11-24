package com.gustler.backend.forecasting.api.forecast;

import java.time.Instant;
import java.util.Objects;

/**
 * 예보가 관측 배치 하나의 예보 실행을 저장했다는 사건이다. 예측을 내지 않은 실행(늦음·빈 응답·범위 밖·입력 부족)도 채점 단계가
 * 시간을 전진시킬 수 있게 낸다. 수집에 실패한 배치는 예보가 처리하지 않으므로 이 사건이 없고, 채점의 노선 시각은 수집에 성공한
 * 배치로만 전진한다.
 *
 * <p>수집 구멍({@code SOURCE_HISTORY_INCOMPLETE})은 저장 줄 수({@code storedRows})와 실제로 남은 관측 줄 수({@code savedRows})가
 * 다른 성공 배치다. 채점 단계는 성공 배치에서 두 값이 다르면 구멍으로 보고, {@code savedRows}를 하루 관측
 * 상한({@code OBSERVATION_INPUT_LIMIT}) 계산에 더한다.
 *
 * @param observationBatchId 관측 배치
 * @param routeId 노선
 * @param routeVersionId 배치가 속한 노선 판본
 * @param receivedAt 응답 수신 시각. 채점 단계가 노선 시각을 이 시각까지 전진시킨다
 * @param result 수집 결과
 * @param storedRows 배치에 기록한 저장 줄 수({@code observation_batch.stored_rows}). 0 이상이다
 * @param savedRows 배치에 실제로 남은 관측 줄 수. 0 이상이다
 */
public record ObservationBatchSeen(
    long observationBatchId,
    long routeId,
    long routeVersionId,
    Instant receivedAt,
    CollectionResult result,
    int storedRows,
    int savedRows
) implements ForecastBatchEvents.Event {

    public ObservationBatchSeen {
        Objects.requireNonNull(receivedAt, "응답 수신 시각이 필요하다");
        Objects.requireNonNull(result, "수집 결과가 필요하다");
        if (storedRows < 0 || savedRows < 0) {
            throw new IllegalArgumentException("줄 수는 0 이상이어야 한다: %d, %d".formatted(storedRows, savedRows));
        }
    }

    /** 수집 구멍인 배치인지 돌려준다. 저장 줄 수가 있는 성공 배치에서 실제 줄 수가 다르면 참이다. */
    public boolean incomplete() {
        return result == CollectionResult.SUCCESS && storedRows > 0 && storedRows != savedRows;
    }

    /** 하루 관측 상한 계산에 더할 줄 수를 돌려준다. 저장 줄 수가 있는 성공 배치면 실제로 남은 줄 수, 아니면 0이다. */
    public int countedRows() {
        if (result == CollectionResult.SUCCESS && storedRows > 0) {
            return savedRows;
        }
        return 0;
    }
}
