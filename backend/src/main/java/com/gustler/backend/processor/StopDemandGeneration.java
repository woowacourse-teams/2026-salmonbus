package com.gustler.backend.processor;

import java.time.Instant;
import java.util.List;

/**
 * 집계 한 판의 산출물. 같은 키를 덮어쓴다.
 *
 * <p>{@code revision} 은 판마다 오른다. z화가 세대 안에서 닫힌 연산이라 세대가 바뀌면 같은 입력이
 * 다른 z 를 낸다. 예보 행에 이 번호를 남겨야 채점을 세대별로 가를 수 있다.
 *
 * <p>{@code dataUntil} 은 이 시각까지 회수된 라벨만 들어갔다는 뜻이다. 누출을 막는 기준선이고
 * 다시 돌렸을 때 같은 값이 나오게 하는 근거이기도 하다.
 */
public record StopDemandGeneration(
    long routeVersionId,
    String calculationVersion,
    int revision,
    Instant dataUntil,
    Instant computedAt,
    List<StopDemandMeasurement> measurements
) {

    private static final int FIRST_REVISION = 1;

    public StopDemandGeneration {
        measurements = List.copyOf(measurements);
        if (revision < FIRST_REVISION) {
            throw new IllegalArgumentException("세대 번호는 %d부터다: %d".formatted(FIRST_REVISION, revision));
        }
        if (calculationVersion == null || calculationVersion.isBlank()) {
            throw new IllegalArgumentException("세대에는 계산 규칙 판이 있어야 한다: " + calculationVersion);
        }
    }
}
