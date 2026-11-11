package com.gustler.backend.api.salmon.application;

import com.gustler.backend.api.boarding.application.BoardingOptionsQuery;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 연어행동 조회의 운영 값. 범위를 벗어난 값이 있으면 애플리케이션이 뜨지 않는다.
 *
 * @param enabled 꺼져 있으면 조회는 판정하지 않는다
 * @param maxWalk 이동 후보의 도보 합 상한. 도보 조회가 답하는 30분보다 길면 그 사이의 도보가 목록에서 빠지므로 거절한다
 * @param gainThreshold 옮겨 타라고 하려면 필요한 이득의 하한. 0 초과 1 이하다
 * @param horizon 요청 시각에 더하는 마감. 이 시각보다 늦게 타면 그 요청에서는 실패다
 * @param maxInVehicle 계획 차내 시간 합의 상한. 기다리는 시간은 넣지 않는다
 * @param eta 못 탈 확률이 이 폭 안이면 같은 묶음으로 보고 도보가 짧은 쪽을 고른다. 0이면 더 낮은 쪽만 남는다
 * @param alpha 도보 시간에 곱한 뒤 마감과 비교한다. 1 이상이다
 * @param maxTransfers 허용 환승 횟수
 * @param minConnection 승차 위치에 도착한 뒤 버스가 오기 전에 남겨야 하는 여유. 0은 허용한다
 */
@ConfigurationProperties("salmon.action")
public record SalmonActionSettings(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("15m") Duration maxWalk,
    @DefaultValue("0.5") double gainThreshold,
    @DefaultValue("20m") Duration horizon,
    @DefaultValue("20m") Duration maxInVehicle,
    @DefaultValue("0") double eta,
    @DefaultValue("1.2") BigDecimal alpha,
    @DefaultValue("1") int maxTransfers,
    @DefaultValue("15s") Duration minConnection
) {
    private static final Duration LONGEST_LISTED_WALK = Duration.ofMinutes(BoardingOptionsQuery.MAX_MINUTES);

    public SalmonActionSettings {
        requirePositive(maxWalk, "max-walk");
        if (maxWalk.compareTo(LONGEST_LISTED_WALK) > 0) {
            throw new IllegalArgumentException(
                "max-walk must not exceed " + LONGEST_LISTED_WALK.toMinutes() + " minutes");
        }
        if (!(gainThreshold > 0 && gainThreshold <= 1)) {
            throw new IllegalArgumentException("gain-threshold must be within (0, 1]");
        }
        requirePositive(horizon, "horizon");
        requirePositive(maxInVehicle, "max-in-vehicle");
        if (!Double.isFinite(eta) || eta < 0) {
            throw new IllegalArgumentException("eta must be zero or positive");
        }
        if (alpha == null || alpha.compareTo(BigDecimal.ONE) < 0) {
            throw new IllegalArgumentException("alpha must be at least 1");
        }
        if (maxTransfers < 0) {
            throw new IllegalArgumentException("max-transfers must be zero or positive");
        }
        if (minConnection == null || minConnection.isNegative()) {
            throw new IllegalArgumentException("min-connection must be zero or positive");
        }
    }

    public boolean walkExceeded(long walkMillis) {
        return walkMillis > maxWalk.toMillis();
    }

    /** 도보 시간에 alpha를 곱해 올림한 값이 마감보다 길면 걸어가는 동안 마감이 지난다. */
    public boolean walkOutlastsHorizon(long walkMillis) {
        return alpha.multiply(BigDecimal.valueOf(walkMillis)).setScale(0, RoundingMode.CEILING).longValueExact()
            > horizon.toMillis();
    }

    public boolean transfersExceeded(int transfers) {
        return transfers > maxTransfers;
    }

    public boolean inVehicleExceeded(long inVehicleMillis) {
        return inVehicleMillis > maxInVehicle.toMillis();
    }

    public boolean connected(long busArrivalMillis, long personReadyMillis) {
        return busArrivalMillis - personReadyMillis >= minConnection.toMillis();
    }

    /** 도보 조회에 넘기는 상한. 분으로 올림한다. */
    public int maxWalkMinutes() {
        return (int) Math.max(1L, (maxWalk.toMillis() + 59_999L) / 60_000L);
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
