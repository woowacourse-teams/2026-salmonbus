package com.gustler.backend.stations.api;

import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;

/**
 * 채우기 배치의 키·하루 상한·원천 남은 값 하한·차단 대기·재채우기·재시도 값이다.
 *
 * <p>maxFailures는 한 회차에서 연속으로 실패해도 되는 횟수다. 닿으면 그 칸의 접수를 거둔다.
 */
public record StationFillPolicy(
    String keyAlias,
    int dailyLimit,
    int remainingFloor,
    Duration blockWait,
    Duration blockWaitMaximum,
    Duration refreshInterval,
    Duration retryBase,
    Duration retryMaximum,
    int maxFailures,
    Duration salmonTileCheckInterval
) {

    public StationFillPolicy {
        if (hasInvalidKey(keyAlias) || hasInvalidBudget(dailyLimit, remainingFloor)) {
            throw new IllegalArgumentException("정류장 채우기의 키·하루 상한·하한을 확인해야 한다");
        }
        if (maxFailures < 1) {
            throw new IllegalArgumentException("정류장 칸의 연속 실패 상한은 1 이상이어야 한다");
        }
        if (hasMissingDuration(
            blockWait,
            blockWaitMaximum,
            refreshInterval,
            retryBase,
            retryMaximum,
            salmonTileCheckInterval
        )) {
            throw new IllegalArgumentException("정류장 채우기의 시간 설정이 필요하다");
        }
    }

    private static boolean hasInvalidKey(String keyAlias) {
        return keyAlias == null || keyAlias.isBlank();
    }

    private static boolean hasInvalidBudget(final int dailyLimit, final int remainingFloor) {
        return dailyLimit < 1 || remainingFloor < 0;
    }

    private static boolean hasMissingDuration(Duration... durations) {
        return Arrays.stream(durations).anyMatch(Objects::isNull);
    }
}
