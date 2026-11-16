package com.gustler.backend.forecasting.application.forecast;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * {@link ObservationBatchReader}가 읽은 배치 하나다. 예보 단계에 넣을 배치와, 기록·사건을 만들 때 필요한 배치 밖 값을 함께 담는다.
 *
 * @param batch 예보 단계에 넣을 배치. 착수 시각은 읽은 원본이 정한 값(예보 실행의 착수 시각, 없으면 배치의
 *     {@code input_confirmed_at})이며 비어 있을 수 있다
 * @param routeId 배치 노선 판본의 노선
 * @param scheduledAt 배치 예약 시각. 나눔 날짜의 기준이다
 * @param capacitySourceId 정원을 찾을 수집 출처({@code route.source_id})
 * @param sourceRowNumbers 관측 번호별 배치 응답 안 줄 번호
 */
public record ObservationBatchRead(
    ForecastBatch batch,
    long routeId,
    Instant scheduledAt,
    String capacitySourceId,
    Map<Long, Integer> sourceRowNumbers
) {
    public ObservationBatchRead {
        Objects.requireNonNull(batch);
        Objects.requireNonNull(scheduledAt);
        sourceRowNumbers = Map.copyOf(sourceRowNumbers);
    }

    /**
     * 배치의 착수 시각만 바꾼 읽기 결과를 돌려준다.
     *
     * @param at 착수 시각
     * @return 나머지 값이 같은 읽기 결과
     */
    public ObservationBatchRead withConfirmedAt(Instant at) {
        return new ObservationBatchRead(batch.withConfirmedAt(at), routeId, scheduledAt, capacitySourceId,
            sourceRowNumbers);
    }
}
