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
    int batchLimit,
    int pendingLimit,
    int arrivalLimit
) {

    public ForecastProperties {
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
}
