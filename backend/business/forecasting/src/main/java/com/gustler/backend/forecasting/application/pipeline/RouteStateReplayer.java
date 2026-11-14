package com.gustler.backend.forecasting.application.pipeline;

import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import java.time.Duration;
import java.time.Instant;

/**
 * 다시 뜰 때 노선 상태를 최근 확정 배치로 되감아 만든다. 예보를 만들지 않고 상태만 전진시킨다.
 * 창의 길이는 설정 {@code forecast.replay-window}(기본 6시간)다. 창 안 어느 배치라도 저장 줄 수와 실제 줄 수가 다르면
 * 그 노선의 되감기를 실패로 돌려 예보하지 않게 한다. 창 안에서 운행 시작이 보이지 않은 차량의 처리는 {@link RouteForecastState}를 따른다.
 */
public final class RouteStateReplayer {
    public static final Duration DEFAULT_WINDOW = Duration.ofHours(6);
    private final ReplaySource source;
    private final ForecastStage stage;
    private final Duration window;

    public RouteStateReplayer(ReplaySource source, ForecastStage stage, Duration window) {
        if (window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("되감기 창은 양수여야 한다");
        }
        this.source = source;
        this.stage = stage;
        this.window = window;
    }

    /** (착수 시각, 배치 번호)가 ({@code through}, {@code throughBatchId}) 이하인 확정 배치까지 반영한 상태를 만든다. */
    public Result replay(AlightingAwareRouteStatistics statistics, Instant through, final long throughBatchId) {
        Instant from = through.minus(window);
        ReplaySource.Read read = source.read(statistics, from, through, throughBatchId);
        if (!read.succeeded()) {
            return Result.failed(read.failureReason());
        }
        RouteForecastState state = RouteForecastState.replayingFrom(statistics.route(), from);
        int applied = 0;
        for (ForecastBatch batch : read.batches()) {
            ForecastStageResult result = stage.replay(state, statistics, batch);
            if (result.status() == ForecastStageResult.Status.REPLAY_REQUIRED) {
                return Result.failed(result.reason());
            }
            state = result.state();
            applied++;
        }
        return new Result(state, null, applied);
    }

    /** 되감은 상태와 반영한 배치 수, 또는 실패 사유다. 실패면 상태가 없다. */
    public record Result(RouteForecastState state, String failureReason, int batches) {
        public Result {
            if ((state == null) == (failureReason == null)) {
                throw new IllegalArgumentException("되감기 결과는 상태와 실패 사유 중 하나만 가진다");
            }
        }

        static Result failed(String reason) {
            return new Result(null, reason, 0);
        }

        public boolean succeeded() {
            return state != null;
        }
    }
}
