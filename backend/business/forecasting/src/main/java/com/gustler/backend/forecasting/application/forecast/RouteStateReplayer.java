package com.gustler.backend.forecasting.application.forecast;

import com.gustler.backend.forecasting.api.forecast.PassClosed;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 다시 뜰 때 노선 상태를 최근 확정 배치로 되감아 만든다. 예보를 만들지 않고 상태만 전진시킨다.
 * 창의 길이는 생성자가 받으며 worker는 {@link #DEFAULT_WINDOW}를 쓴다. 창 안 어느 배치라도 저장 줄 수와 실제 줄 수가 다르면
 * 그 노선의 되감기를 실패로 돌려 예보하지 않게 한다. 창 안에서 운행 시작이 보이지 않은 차량의 처리는 {@link RouteForecastState}를 따른다.
 *
 * <p>되감은 상태가 모자라면({@link RouteForecastState#replayShortfallAt}) 창을 한 번 늘려(worker는
 * {@link #DEFAULT_EXTENDED_WINDOW}) 다시 되감는다. 한 운행이나 한 연결이 창보다 길게 이어지는 중에 다시 뜬 경우다. 늘린 창으로 덜
 * 모자라면 그 상태를 쓰고, 그렇지 않거나 늘린 창을 읽지 못하면 처음 되감은 상태를 그대로 쓴다. 모자람이 없으면 두 번째 되감기를
 * 돌리지 않는다.
 *
 * <p>닫은 통과가 저장되지 않은 배치({@link ReplaySource.Read#unforecastRuns})는 되감으면서 그 배치가 닫은 통과를 함께 판정해
 * 돌려준다. 예보 단계를 돌리지 못하고 끝난 배치와 이전 배포본이 처리한 배치다. 저장과 전달은 부르는 쪽이 한다.
 */
public final class RouteStateReplayer {
    /** 처음 되감는 창. */
    public static final Duration DEFAULT_WINDOW = Duration.ofHours(6);
    /**
     * 늘려 되감는 창. 관측을 되감는 가장 긴 범위다. 이관 앱은 다시 뜬 worker가 다시 읽는 가장 긴 범위
     * ({@code PartitionExportSettings.LONGEST_REPLAY})로 보관 날짜 수의 최솟값을 정하고, 관측은 이 창까지만 되감는다고 본다. 이 창을
     * 그 범위보다 길게 늘리려면 그 값도 함께 늘려야 한다. 그러지 않으면 이관된 관측이 창 안에서 빠진 채로 상태를 만들 수 있다.
     */
    public static final Duration DEFAULT_EXTENDED_WINDOW = Duration.ofHours(12);
    private final ReplaySource source;
    private final ForecastStage stage;
    private final Duration window;
    private final Duration extendedWindow;

    /**
     * @param window 처음 되감는 창
     * @param extendedWindow 되감은 상태가 모자랄 때 한 번 늘려 되감는 창. {@code window}보다 길지 않으면 늘리지 않는다
     */
    public RouteStateReplayer(
        ReplaySource source,
        ForecastStage stage,
        Duration window,
        Duration extendedWindow
    ) {
        if (window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("되감기 창은 양수여야 한다");
        }
        this.source = source;
        this.stage = stage;
        this.window = window;
        this.extendedWindow = extendedWindow;
    }

    /** (착수 시각, 배치 번호)가 ({@code through}, {@code throughBatchId}) 이하인 확정 배치까지 반영한 상태를 만든다. */
    public Result replay(AlightingAwareRouteStatistics statistics, Instant through, final long throughBatchId) {
        Result first = replay(statistics, through, throughBatchId, window);
        if (!first.succeeded() || extendedWindow.compareTo(window) <= 0) {
            return first;
        }
        RouteForecastState.ReplayShortfall shortfall = first.state().replayShortfallAt(through);
        if (shortfall.none()) {
            return first;
        }
        Result extended = replay(statistics, through, throughBatchId, extendedWindow);
        if (!extended.succeeded()) {
            return first.extended(Extension.FAILED);
        }
        RouteForecastState.ReplayShortfall remaining = extended.state().replayShortfallAt(through);
        if (remaining.none()) {
            return extended.extended(Extension.RESOLVED);
        }
        if (remaining.lessThan(shortfall)) {
            return extended.extended(Extension.UNRESOLVED);
        }
        return first.extended(Extension.UNRESOLVED);
    }

    private Result replay(
        AlightingAwareRouteStatistics statistics,
        Instant through,
        final long throughBatchId,
        Duration length
    ) {
        Instant from = through.minus(length);
        ReplaySource.Read read = source.read(statistics, from, through, throughBatchId);
        if (!read.succeeded()) {
            return Result.failed(read.failureReason());
        }
        RouteForecastState state = RouteForecastState.replayingFrom(statistics.route(), from);
        List<PassClosed> recovered = new ArrayList<>();
        int applied = 0;
        for (ForecastBatch batch : read.batches()) {
            ReplaySource.UnforecastRun unforecast = read.unforecastRuns().get(batch.observationBatchId());
            ForecastStageResult result = advance(state, statistics, batch, unforecast);
            if (result.status() == ForecastStageResult.Status.REPLAY_REQUIRED) {
                return Result.failed(result.reason());
            }
            for (ClosedPass pass : result.closedPasses()) {
                recovered.add(pass.toEvent(unforecast.sourceDate(), unforecast.routeId()));
            }
            state = result.state();
            applied++;
        }
        return new Result(state, null, applied, recovered, Extension.NONE);
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

    /** 창을 늘려 다시 되감았는지와 그 결과다. */
    public enum Extension {
        /** 창을 늘리지 않았다. */
        NONE,
        /** 늘린 창으로 되감아 모자람이 없어졌다. */
        RESOLVED,
        /** 늘린 창으로 되감아도 모자람이 남았다. 둘 가운데 덜 모자란 상태를 쓴다. */
        UNRESOLVED,
        /** 늘린 창을 읽지 못해 처음 되감은 상태를 쓴다. */
        FAILED
    }

    /**
     * 되감은 상태와 반영한 배치 수, 또는 실패 사유다. 실패면 상태가 없다.
     *
     * @param recoveredPasses 닫은 통과가 저장되지 않은 배치가 닫은 통과. 닫힌 순서다. 이미 저장된 것이 섞여 있을 수 있다
     * @param extension 창을 늘려 다시 되감았는지
     */
    public record Result(
        RouteForecastState state,
        String failureReason,
        int batches,
        List<PassClosed> recoveredPasses,
        Extension extension
    ) {
        public Result {
            if ((state == null) == (failureReason == null)) {
                throw new IllegalArgumentException("되감기 결과는 상태와 실패 사유 중 하나만 가진다");
            }
            recoveredPasses = List.copyOf(recoveredPasses);
        }

        static Result failed(String reason) {
            return new Result(null, reason, 0, List.of(), Extension.NONE);
        }

        public boolean succeeded() {
            return state != null;
        }

        private Result extended(Extension result) {
            return new Result(state, failureReason, batches, recoveredPasses, result);
        }
    }
}
