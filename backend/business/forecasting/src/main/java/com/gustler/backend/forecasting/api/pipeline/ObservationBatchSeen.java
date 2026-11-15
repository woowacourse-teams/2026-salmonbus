package com.gustler.backend.forecasting.api.pipeline;

import java.time.Instant;
import java.util.Objects;

/**
 * 관측 배치 하나가 수집 단계를 지났다는 사건이다. 예보를 만들지 않은 배치도 채점 단계가 시간을 전진시킬 수 있게 모든 배치가 낸다.
 *
 * <p>수집 실패는 {@link CollectionResult#FAILED}로 안다. 수집 구멍({@code SOURCE_HISTORY_INCOMPLETE})은
 * 실패 배치가 아니라 저장 줄 수({@code storedRows})와 실제로 남은 관측 줄 수({@code savedRows})가 다른 성공 배치다. 채점 단계는
 * 성공 배치에서 두 값이 다르면 구멍으로 보고, {@code savedRows}를 하루 관측 상한({@code OBSERVATION_INPUT_LIMIT}) 계산에 더한다.
 * 실패한 배치는 응답을 받지 못했을 수 있어 수신 시각이 비어 있을 수 있고, 성공·빈 응답 배치는 수신 시각이 반드시 있다.
 *
 * @param observationBatchId 관측 배치
 * @param routeId 노선
 * @param routeVersionId 배치가 속한 노선 판본
 * @param scheduledAt 배치 예약 시각. 나눔 날짜와 실패 배치의 시간 기준이다
 * @param receivedAt 응답 수신 시각. 실패한 배치에서만 비어 있을 수 있다
 * @param result 수집 결과
 * @param storedRows 배치에 기록한 저장 줄 수({@code observation_batch.stored_rows}). 0 이상이다
 * @param savedRows 배치에 실제로 남은 관측 줄 수. 0 이상이다
 */
public record ObservationBatchSeen(
    long observationBatchId,
    long routeId,
    long routeVersionId,
    Instant scheduledAt,
    Instant receivedAt,
    CollectionResult result,
    int storedRows,
    int savedRows
) {
    public ObservationBatchSeen {
        Objects.requireNonNull(scheduledAt, "배치 예약 시각이 필요하다");
        Objects.requireNonNull(result, "수집 결과가 필요하다");
        if (receivedAt == null && result != CollectionResult.FAILED) {
            throw new IllegalArgumentException("응답을 받은 배치에는 수신 시각이 필요하다: " + result);
        }
        if (storedRows < 0 || savedRows < 0) {
            throw new IllegalArgumentException("줄 수는 0 이상이어야 한다: %d, %d".formatted(storedRows, savedRows));
        }
    }

    /**
     * 줄 수를 모르는 배치의 사건을 만든다. 저장 줄 수와 실제 줄 수를 모두 0으로 두므로 구멍과 상한 계산에 들어가지 않는다.
     *
     * @param observationBatchId 관측 배치
     * @param routeId 노선
     * @param routeVersionId 배치가 속한 노선 판본
     * @param scheduledAt 배치 예약 시각
     * @param receivedAt 응답 수신 시각. 실패한 배치에서만 비어 있을 수 있다
     * @param result 수집 결과
     */
    public ObservationBatchSeen(
        final long observationBatchId,
        final long routeId,
        final long routeVersionId,
        Instant scheduledAt,
        Instant receivedAt,
        CollectionResult result
    ) {
        this(observationBatchId, routeId, routeVersionId, scheduledAt, receivedAt, result, 0, 0);
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
