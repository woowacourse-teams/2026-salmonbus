package com.gustler.backend.collector;

import java.time.OffsetDateTime;

/**
 * 한 판을 언제 부르기로 계획했나.
 *
 * <p>보낸 시각과 받은 시각은 여기 없다. 자리를 잡는 시점에는 아직 안 보내서 모른다.
 * 사다리가 올라가면서 묶음 행에 하나씩 채워진다.
 *
 * <p>attemptKey 는 재시도해도 값이 같다. 한 번의 계획에 묶음이 두 개 생기는 것을 DB 가 막는 근거다.
 * 몇 번째 시도인지는 장부가 센다. 재시도가 한도를 몇 번 썼는지가 그 숫자다.
 */
public record ObservationAttempt(
    long routeVersionId,
    OffsetDateTime scheduledAt,
    String attemptKey
) {
}
