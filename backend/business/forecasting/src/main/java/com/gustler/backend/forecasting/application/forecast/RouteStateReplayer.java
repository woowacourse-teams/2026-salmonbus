package com.gustler.backend.forecasting.application.forecast;

import com.gustler.backend.forecasting.api.forecast.PassClosed;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 재시작할 때 노선 상태를 최근 확정 배치로 복구해 만든다. 예보를 만들지 않고 상태만 갱신한다.
 * 조회 시간 범위는 생성자가 받으며 worker는 {@link #DEFAULT_WINDOW}를 쓴다. 조회 범위 안 어느 배치라도 저장 행 수와 실제 행 수가 다르면
 * 그 노선의 복구를 실패로 돌려 예보하지 않게 한다. 조회 범위 안에서 운행 시작이 보이지 않은 차량의 처리는 {@link RouteForecastState}를 따른다.
 *
 * <p>worker는 최근 6시간을 한 번만 복구한다. 범위 안에서 운행 시작을 찾지 못한 차량은 새 운행을 확인할 때까지 제외한다.
 *
 * <p>확정한 통과가 저장되지 않은 배치({@link ReplaySource.Read#unforecastRuns})는 복구하면서 그 배치가 확정한 통과를 같이 판정해
 * 반환한다. 입력 부족으로 끝난 예보 실행의 배치다. 저장과 전달은 호출자가 한다.
 */
public final class RouteStateReplayer {

    /** 한 번 복구할 범위. */
    public static final Duration DEFAULT_WINDOW = Duration.ofHours(6);

    private final ReplaySource source;
    private final ForecastStage stage;
    private final Duration window;

    /**
     * @param window 한 번 복구할 범위
     */
    public RouteStateReplayer(ReplaySource source, ForecastStage stage, Duration window) {
        if (window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("복구할 시간 범위는 0보다 커야 한다");
        }
        this.source = source;
        this.stage = stage;
        this.window = window;
    }

    /** (처리 시작 시각, 배치 번호)가 ({@code through}, {@code throughBatchId}) 이하인 확정 배치까지 반영한 상태를 만든다. */
    public Result replay(
        AlightingAwareRouteStatistics statistics,
        Instant through,
        final long throughBatchId
    ) {
        Instant from = through.minus(window);
        ReplaySource.Read read = source.read(
                statistics,
                from,
                through,
                throughBatchId
        );
        if (!read.succeeded()) {
            return Result.failed(read.failureReason());
        }
        RouteForecastState state = RouteForecastState.replayingFrom(statistics.route(), from);
        List<PassClosed> recovered = new ArrayList<>();
        int applied = 0;
        for (ForecastBatch batch : read.batches()) {
            ReplaySource.UnforecastRun unforecast = read.unforecastRuns().get(batch.observationBatchId());
            ForecastStageResult result = advance(
                    state,
                    statistics,
                    batch,
                    unforecast
            );
            if (result.status() == ForecastStageResult.Status.REPLAY_REQUIRED) {
                return Result.failed(result.reason());
            }
            for (ClosedPass pass : result.closedPasses()) {
                recovered.add(pass.toEvent(unforecast.sourceDate(), unforecast.routeId()));
            }
            state = result.state();
            applied++;
        }
        return new Result(
                state,
                null,
                applied,
                recovered
        );
    }

    private ForecastStageResult advance(
        RouteForecastState state,
        AlightingAwareRouteStatistics statistics,
        ForecastBatch batch,
        ReplaySource.UnforecastRun unforecast
    ) {
        if (unforecast == null) {
            return stage.replay(state, statistics, batch);
        }
        return stage.replayClosing(state, statistics, batch);
    }

    /**
     * 복구한 상태와 반영한 배치 수, 또는 실패 사유다. 실패면 상태가 없다.
     *
     * @param recoveredPasses 확정한 통과가 저장되지 않은 배치가 확정한 통과. 닫힌 순서다. 이미 저장된 것이 섞여 있을 수 있다
     */
    public record Result(
        RouteForecastState state,
        String failureReason,
        int batches,
        List<PassClosed> recoveredPasses
    ) {

        public Result {
            if ((state == null) == (failureReason == null)) {
                throw new IllegalArgumentException("복구 결과에는 노선 상태와 실패 사유 중 하나만 있어야 한다");
            }
            recoveredPasses = List.copyOf(recoveredPasses);
        }

        static Result failed(String reason) {
            return new Result(
                    null,
                    reason,
                    0,
                    List.of()
            );
        }

        public boolean succeeded() {
            return state != null;
        }

    }
}
