package com.gustler.backend.worker.configuration;

import com.gustler.backend.forecasting.api.ForecastPolicy;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 예보와 채점의 설정.
 *
 * <p>기본값은 꺼짐이다. 계수 번들이 없어 활성 배포가 하나도 없는 상태에서 켜면
 * 아무 값도 내지 않으면서 몇 초마다 빈 질의만 실행한다. 배포 행이 생성된 뒤에 켠다.
 *
 * @param interval 수집 신호를 놓쳤거나 처리 중에 멈춘 배치를 다시 줍는 주기
 * @param staleness 수신한 지 이보다 오래된 배치는 줍지 않는다
 * @param batchLimit 노선 판본의 처리하지 않은 배치를 한 번에 읽는 수의 상한
 * @param lanes 노선별 실행 줄 수. 같은 노선은 항상 같은 줄에서 순서대로 처리한다
 */
@ConfigurationProperties("forecast")
public record ForecastProperties(
    boolean enabled,
    Duration interval,
    Duration staleness,
    int batchLimit,
    @DefaultValue("2") int lanes
) {

    /**
     * 신선도 창의 상한.
     *
     * <p>큐가 관측 시각 오름차순이라 창을 넓히면 오래된 관측부터 회차를 채운다. 창이 옮겨 넣은
     * 관측의 나이를 넘는 순간 한 회차가 그 관측으로 가득 차서 방금 들어온 관측은 한 개도 포함하지 못하고,
     * 옮겨 온 관측에 예보가 붙어 되돌리기까지 막힌다. 운영에 사용할 값은 몇 분 단위라 한 시간이면 넉넉하다.
     */
    public static final Duration MAX_STALENESS = ForecastPolicy.MAX_STALENESS;

    public ForecastProperties {
        requirePositive("forecast.staleness", staleness);
        requireAtMost("forecast.staleness", staleness, MAX_STALENESS);
        requirePositive("forecast.batch-limit", batchLimit);
        requirePositive("forecast.lanes", lanes);
    }

    private static void requirePositive(String name, final int value) {
        if (value <= 0) {
            throw new IllegalStateException("%s 는 1 이상이어야 한다: %d".formatted(name, value));
        }
    }

    private static void requirePositive(String name, Duration value) {
        if (!isPositive(value)) {
            throw new IllegalStateException("%s 는 0보다 길어야 한다: %s".formatted(name, value));
        }
    }

    private static boolean isPositive(Duration value) {
        if (value == null || value.isNegative()) {
            return false;
        }
        return !value.isZero();
    }

    private static void requireAtMost(String name, Duration value, Duration limit) {
        if (value.compareTo(limit) > 0) {
            throw new IllegalStateException("%s 는 %s 이하여야 한다: %s".formatted(name, limit, value));
        }
    }
}
