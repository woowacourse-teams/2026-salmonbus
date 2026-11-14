package com.gustler.backend.forecasting.api.pipeline;

import java.time.Instant;
import java.util.Objects;

/**
 * 관측 배치 하나가 수집 단계를 지났다는 사건이다. 예보를 만들지 않은 배치도 채점 단계가 시간을 전진시킬 수 있게 모든 배치가 낸다.
 *
 * <p>수집 구멍은 채점 단계가 이 흐름의 {@link CollectionResult#FAILED}에서 직접 안다. 실패한 배치는 응답을 받지 못했을 수 있어
 * 수신 시각이 비어 있을 수 있고, 성공·빈 응답 배치는 수신 시각이 반드시 있다.
 *
 * @param observationBatchId 관측 배치
 * @param routeId 노선
 * @param routeVersionId 배치가 속한 노선 판본
 * @param scheduledAt 배치 예약 시각. 나눔 날짜와 실패 배치의 시간 기준이다
 * @param receivedAt 응답 수신 시각. 실패한 배치에서만 비어 있을 수 있다
 * @param result 수집 결과
 */
public record ObservationBatchSeen(
    long observationBatchId,
    long routeId,
    long routeVersionId,
    Instant scheduledAt,
    Instant receivedAt,
    CollectionResult result
) {
    public ObservationBatchSeen {
        Objects.requireNonNull(scheduledAt, "배치 예약 시각이 필요하다");
        Objects.requireNonNull(result, "수집 결과가 필요하다");
        if (receivedAt == null && result != CollectionResult.FAILED) {
            throw new IllegalArgumentException("응답을 받은 배치에는 수신 시각이 필요하다: " + result);
        }
    }
}
