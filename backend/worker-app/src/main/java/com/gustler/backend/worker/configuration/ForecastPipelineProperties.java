package com.gustler.backend.worker.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 새 예보 경로의 스위치다. 기본값은 모두 옛 경로만 도는 상태다.
 *
 * <p>운영 전환 순서는 {@code shadow.enabled=true} → {@code scoring=shadow} → {@code pipeline=v2} → {@code scoring=v2}다.
 * 이 묶음은 스위치를 읽어 파티션 유지 작업을 켜는 데만 쓰고, 예보·채점 경로는 뒤 묶음이 이 값을 읽는다.
 *
 * @param pipeline 본선 경로
 * @param shadow 옛 경로 옆에서 새 경로를 돌리는 그림자 실행
 * @param scoring 새 채점 단계의 동작
 * @param lanes 새 경로의 노선별 실행 줄 수
 * @param replayWindow 다시 뜰 때 되감는 관측 범위
 */
@ConfigurationProperties("forecast")
public record ForecastPipelineProperties(
    @DefaultValue("v1") Pipeline pipeline,
    @DefaultValue Shadow shadow,
    @DefaultValue("off") Scoring scoring,
    @DefaultValue("2") int lanes,
    @DefaultValue("6h") Duration replayWindow
) {
    public ForecastPipelineProperties {
        if (lanes < 1) {
            throw new IllegalStateException("forecast.lanes 는 1 이상이어야 한다: " + lanes);
        }
        if (replayWindow == null || replayWindow.isNegative() || replayWindow.isZero()) {
            throw new IllegalStateException("forecast.replay-window 는 0보다 길어야 한다: " + replayWindow);
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
        /** 새 채점을 돌려 새 테이블에 쓰고 옛 결과와 대조한다. */
        SHADOW,
        /** 새 채점이 본선이고 옛 정산 작업을 돌리지 않는다. */
        V2
    }

    /**
     * 그림자 실행 설정.
     *
     * @param enabled {@code pipeline=v1}일 때 새 경로를 옆에서 돌려 예측값을 대조하고 새 테이블에 쓴다
     */
    public record Shadow(@DefaultValue("false") boolean enabled) {
    }
}
