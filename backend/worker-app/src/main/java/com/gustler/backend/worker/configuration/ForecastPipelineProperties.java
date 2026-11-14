package com.gustler.backend.worker.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 새 예보 경로의 스위치다. 기본값은 모두 옛 경로만 도는 상태다.
 *
 * <p>운영 전환 순서는 {@code shadow.enabled=true} → {@code scoring=shadow} → {@code pipeline=v2} → {@code scoring=v2}다.
 * 파티션 유지 작업, 그림자 실행 줄, 그림자 채점 줄이 이 값을 읽는다. 새 경로의 본선 전환은 뒤 묶음이 읽는다.
 *
 * <p>채점({@code scoring})은 그림자 실행이 켜졌을 때만 돈다. 사건이 그림자 실행에서만 오기 때문이다. {@code scoring=v2}는 이
 * 묶음에서 {@code shadow}와 똑같이 동작한다(옛 정산을 끄는 것은 다음 묶음이 한다).
 *
 * @param pipeline 본선 경로
 * @param shadow 옛 경로 옆에서 새 경로를 돌리는 그림자 실행
 * @param scoring 새 채점 단계의 동작
 * @param lanes 새 경로의 노선별 실행 줄 수
 * @param replayWindow 다시 뜰 때 되감는 관측 범위
 * @param scoringFlushInterval 채점 결과를 모았다가 한 트랜잭션으로 쓰는 간격. 같은 간격으로 마감 정리도 한다
 * @param scoringQueueCapacity 채점 줄에 기다릴 수 있는 일의 수. 넘치면 사건을 버리고 그 노선을 다시 되감는다
 * @param scoringComparisonInterval 새 채점 결과를 옛 정산 결과와 대조하는 간격
 * @param scoringComparisonDelay 수신 뒤 이만큼 지난 배치만 대조한다. 옛 정산이 결과를 다 적을 시간이다
 * @param scoringComparisonBatchLimit 대조 한 회차에 읽는 예보 실행 수의 상한
 */
@ConfigurationProperties("forecast")
public record ForecastPipelineProperties(
    @DefaultValue("v1") Pipeline pipeline,
    @DefaultValue Shadow shadow,
    @DefaultValue("off") Scoring scoring,
    @DefaultValue("2") int lanes,
    @DefaultValue("6h") Duration replayWindow,
    @DefaultValue("5s") Duration scoringFlushInterval,
    @DefaultValue("10000") int scoringQueueCapacity,
    @DefaultValue("10m") Duration scoringComparisonInterval,
    @DefaultValue("3h") Duration scoringComparisonDelay,
    @DefaultValue("1500") int scoringComparisonBatchLimit
) {
    public ForecastPipelineProperties {
        if (lanes < 1) {
            throw new IllegalStateException("forecast.lanes 는 1 이상이어야 한다: " + lanes);
        }
        if (replayWindow == null || replayWindow.isNegative() || replayWindow.isZero()) {
            throw new IllegalStateException("forecast.replay-window 는 0보다 길어야 한다: " + replayWindow);
        }
        requirePositive("forecast.scoring-flush-interval", scoringFlushInterval);
        requirePositive("forecast.scoring-comparison-interval", scoringComparisonInterval);
        if (scoringComparisonDelay == null || scoringComparisonDelay.isNegative()) {
            throw new IllegalStateException("forecast.scoring-comparison-delay 는 0 이상이어야 한다: "
                + scoringComparisonDelay);
        }
        if (scoringQueueCapacity < 1) {
            throw new IllegalStateException("forecast.scoring-queue-capacity 는 1 이상이어야 한다: " + scoringQueueCapacity);
        }
        if (scoringComparisonBatchLimit < 1) {
            throw new IllegalStateException("forecast.scoring-comparison-batch-limit 는 1 이상이어야 한다: "
                + scoringComparisonBatchLimit);
        }
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalStateException(name + " 는 0보다 길어야 한다: " + value);
        }
    }

    /** 새 경로가 쓰는 테이블이 필요한 상태인지 돌려준다. 하나라도 기본값이 아니면 참이다. */
    public boolean usesPipelineTables() {
        return shadow.enabled() || pipeline == Pipeline.V2 || scoring != Scoring.OFF;
    }

    /** 본선 경로. */
    public enum Pipeline {
        /** 옛 경로가 본선이다. */
        V1,
        /** 새 경로가 본선이다. 옛 예보 쓰기와 체크포인트를 하지 않는다. */
        V2
    }

    /** 새 채점 단계의 동작. */
    public enum Scoring {
        /** 새 채점을 돌리지 않는다. */
        OFF,
        /** 새 채점을 돌려 새 테이블에 쓰고 옛 결과와 대조한다. 그림자 실행이 켜져 있어야 돈다. */
        SHADOW,
        /** 새 채점이 본선이고 옛 정산 작업을 돌리지 않는다. 이 묶음에서는 {@link #SHADOW}와 똑같이 동작한다. */
        V2
    }

    /**
     * 채점이 켜졌는데 사건을 낼 그림자 실행이 돌지 않는 설정인지 돌려준다.
     *
     * @param forecastEnabled {@code forecast.enabled}
     * @return 채점을 켰으나 그림자 실행 조건({@code forecast.enabled}, {@code shadow.enabled}, {@code pipeline=v1})이 아니면 참
     */
    public boolean scoringWithoutShadow(final boolean forecastEnabled) {
        return scoring != Scoring.OFF && !(forecastEnabled && shadow.enabled() && pipeline == Pipeline.V1);
    }

    /**
     * 그림자 실행 설정.
     *
     * @param enabled {@code pipeline=v1}일 때 새 경로를 옆에서 돌려 예측값을 대조하고 새 테이블에 쓴다
     * @param queueCapacity 그림자 실행 줄에 기다릴 수 있는 배치 수. 넘치면 새 배치를 버리고 그 노선의 상태를 버린다
     */
    public record Shadow(@DefaultValue("false") boolean enabled, @DefaultValue("200") int queueCapacity) {
        public Shadow {
            if (queueCapacity < 1) {
                throw new IllegalStateException("forecast.shadow.queue-capacity 는 1 이상이어야 한다: " + queueCapacity);
            }
        }
    }
}
