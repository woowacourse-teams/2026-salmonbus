package com.gustler.backend.worker.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 예보의 노선별 실행 줄, 채점 줄, 되감기, 정상 종료 설정이다.
 *
 * @param lanes 노선별 실행 줄 수
 * @param replayWindow 다시 뜰 때 되감는 관측 범위
 * @param scoringFlushInterval 채점 결과를 모았다가 한 트랜잭션으로 쓰는 간격. 같은 간격으로 마감 정리도 한다
 * @param scoringQueueCapacity 채점 줄에 기다릴 수 있는 일의 수. 넘치면 사건을 버리고 그 노선을 다시 되감는다
 * @param laneQueueCapacity 노선별 실행 줄 하나에 기다릴 수 있는 배치 수. 넘치면 넣지 않고 다음 줍기에 맡긴다
 * @param shutdownTimeout 정상 종료 때 실행 줄과 채점 줄이 받은 일을 끝내기를 기다리는 한도. 지나면 남은 일을 버리고 내려간다.
 *     스프링의 단계별 종료 시한과 더해도 서비스 관리자의 강제 종료 시한보다 짧아야 한다. 0이면 기다리지 않는다
 */
@ConfigurationProperties("forecast")
public record ForecastPipelineProperties(
    @DefaultValue("2") int lanes,
    @DefaultValue("6h") Duration replayWindow,
    @DefaultValue("5s") Duration scoringFlushInterval,
    @DefaultValue("10000") int scoringQueueCapacity,
    @DefaultValue("1000") int laneQueueCapacity,
    @DefaultValue("10s") Duration shutdownTimeout
) {
    public ForecastPipelineProperties {
        if (lanes < 1) {
            throw new IllegalStateException("forecast.lanes 는 1 이상이어야 한다: " + lanes);
        }
        requirePositive("forecast.replay-window", replayWindow);
        requirePositive("forecast.scoring-flush-interval", scoringFlushInterval);
        if (scoringQueueCapacity < 1) {
            throw new IllegalStateException("forecast.scoring-queue-capacity 는 1 이상이어야 한다: " + scoringQueueCapacity);
        }
        if (laneQueueCapacity < 1) {
            throw new IllegalStateException("forecast.lane-queue-capacity 는 1 이상이어야 한다: " + laneQueueCapacity);
        }
        if (shutdownTimeout == null || shutdownTimeout.isNegative()) {
            throw new IllegalStateException("forecast.shutdown-timeout 은 0 이상이어야 한다: " + shutdownTimeout);
        }
    }

    private static void requirePositive(String name, Duration value) {
        if (isNotPositive(value)) {
            throw new IllegalStateException(name + " 는 0보다 길어야 한다: " + value);
        }
    }

    private static boolean isNotPositive(Duration value) {
        return value == null || value.isNegative() || value.isZero();
    }
}
