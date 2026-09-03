package com.gustler.backend.processor;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 예보 배치와 라벨 회수 배치의 설정.
 *
 * <p>기본값은 꺼짐이다. 계수 번들이 없어 도는 배포가 하나도 없고, 그 상태로 배치를 켜면
 * 아무 값도 안 내면서 몇 초마다 빈 질의만 돈다. 켜는 것은 배포 행이 선 뒤다.
 */
@ConfigurationProperties("forecast")
public record ForecastProperties(
    boolean enabled,
    Duration interval,
    Duration settlementInterval,
    Duration statisticsInterval,
    Duration staleness,
    int batchLimit,
    int pendingLimit,
    int arrivalLimit
) {

    /**
     * 신선도 창의 상한.
     *
     * <p>큐가 관측 시각 오름차순이라 창을 넓히면 오래된 판부터 회차의 자리를 채운다. 창이 옮겨 넣은
     * 관측의 나이를 넘는 순간 한 회차가 그것으로 다 차서 방금 들어온 판이 한 개도 안 들어가고,
     * 옮겨 온 관측에 예보가 붙어 되돌리기까지 막힌다. 운영으로 쓸 값이 몇 분 단위라 한 시간이면 넉넉하다.
     */
    private static final Duration MAX_STALENESS = Duration.ofHours(1);

    public ForecastProperties {
        requirePositive("forecast.staleness", staleness);
        requireAtMost("forecast.staleness", staleness, MAX_STALENESS);
        requirePositive("forecast.batch-limit", batchLimit);
        requirePositive("forecast.pending-limit", pendingLimit);
        requirePositive("forecast.arrival-limit", arrivalLimit);
    }

    private static void requirePositive(
        String name,
        final int value
    ) {
        if (value <= 0) {
            throw new IllegalStateException("%s 는 1 이상이어야 한다: %d".formatted(name, value));
        }
    }

    private static void requirePositive(
        String name,
        Duration value
    ) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalStateException("%s 는 0보다 길어야 한다: %s".formatted(name, value));
        }
    }

    private static void requireAtMost(
        String name,
        Duration value,
        Duration limit
    ) {
        if (value.compareTo(limit) > 0) {
            throw new IllegalStateException("%s 는 %s 이하여야 한다: %s".formatted(name, limit, value));
        }
    }
}
