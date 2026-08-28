package com.gustler.backend.collector;

import java.time.OffsetDateTime;

/**
 * 한 판의 호출을 언제 계획해서 언제 보내고 언제 받았나. 몇 번째 시도인지도 같이 든다.
 *
 * <p>attemptKey 는 재시도해도 값이 같다. 한 번의 계획에 묶음이 두 개 생기는 것을 DB 가 막는 근거다.
 * responseReceivedAt 이 관측 시각의 권위다. 상류가 주는 queryTime 이 아니다.
 */
public record ObservationAttempt(
    long routeVersionId,
    OffsetDateTime scheduledAt,
    int attemptNumber,
    String attemptKey,
    OffsetDateTime requestedAt,
    OffsetDateTime responseReceivedAt
) {
}
