package com.gustler.backend.forecasting.application.forecast;

import com.gustler.backend.forecasting.api.ForecastTelemetry;
import com.gustler.backend.forecasting.api.forecast.ForecastBatchEvents;
import com.gustler.backend.forecasting.api.forecast.ForecastClosed;
import com.gustler.backend.forecasting.api.forecast.ForecastPublished;
import com.gustler.backend.forecasting.api.forecast.ObservationBatchSeen;
import com.gustler.backend.forecasting.api.forecast.PassClosed;
import com.gustler.backend.forecasting.api.forecast.ScoringCycle;
import com.gustler.backend.forecasting.api.forecast.ScoringFeed;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 예보가 커밋 뒤에 넘긴 이벤트를 정산 단계({@link ScoringStage})에 차례로 흘리고 결과를 {@link ScoringWriter}에 모으는 정산이다.
 *
 * <p>이벤트를 받는 쪽({@link ForecastEvents})은 일을 {@link ScoringFeed}에 넣기만 한다. 정산 단계는 동시 호출을 지원하지 않으므로
 * 정산 단계와 노선 준비 표시는 그 큐의 전용 스레드에서만 만진다. 노선마다 다음 규칙을 지킨다.
 * <ul>
 *   <li>노선의 이벤트를 처음 처리하기 전에 {@link ScoringReplay}로 저장된 기록을 복구해 상태를 만든다. 기동 직후에는
 *       {@link #start()}가 복구의 예보 범위 상한 안에 예보가 있던 노선을 모두 미리 복구한다. 내려가 있던 동안 마감이 지난
 *       예측은 그 뒤 이벤트가 오지 않는 노선에서도 이 복구와 {@link #tick()}의 마감으로 닫힌다. 이벤트는 커밋 뒤에만 오므로
 *       복구가 그 이벤트를 이미 읽었을 수 있으나, 정산 단계는 같은 이벤트를 다시 받아도 결과 집합이 같다.</li>
 *   <li>큐가 가득 차 이벤트를 버렸으면 그 노선을 다시 복구해야 한다고 표시하고, 다음 일에서 상태를 버리고 복구한다.</li>
 *   <li>처리 중 예외가 나면 그 노선의 상태를 버린다({@link ScoringStage#discard}). 다음 이벤트가 다시 복구한다.</li>
 *   <li>복구가 실패하면 {@value #REPLAY_RETRY_SECONDS}초 동안 그 노선의 이벤트를 처리하지 않고 건너뛴다. 복구하지 못한 상태로
 *       처리하면 직전 통과가 빠진 정답이 저장되어 고칠 수 없기 때문이다. 건너뛴 이벤트는 저장돼 있어 다음 복구가 읽는다.
 *       복구는 정산이 그 노선을 저장까지 마친 이벤트 시각부터 예보를 다시 읽으므로, 실패가 오래 이어져도 그사이 확정된 통과의
 *       정답이 빠지지 않는다.</li>
 * </ul>
 * 결과는 {@link #flush()}가 몇 초마다 쓰고, {@link #tick()}이 정산 스레드에서 {@link ScoringStage#expire}를 불러 오래 배치가
 * 없는 노선의 마감을 닫는다. 내려갈 때 큐에서 기다리던 이벤트와 아직 쓰지 못한 결과는 버려진다. 이벤트는 저장된 기록에서 나온
 * 것이므로 다음 기동의 복구가 같은 결과를 다시 만든다. 정산 큐가 닫히며 정산 스레드에 중단을 요청하면 기동 직후의 복구는
 * 돌던 노선까지만 하고 멈춘다. {@code route} 행 잠금을 사용하지 않는다.
 *
 * <p>쓰기가 커밋되면 새로 저장한 행으로 정산을 마친 예측을 진단 수신자에 알린다({@link ForecastTelemetry#closed}). 복구가 다시 계산한 결과는
 * 줄이 이미 있어 들어가지 않으므로 알리지 않는다. 그래서 한 예측은 그 행을 저장한 프로세스가 한 번만 알린다.
 */
public final class ScoringService implements ForecastEvents, ScoringCycle {

    static final long REPLAY_RETRY_SECONDS = 30;
    private static final Duration REPLAY_RETRY = Duration.ofSeconds(REPLAY_RETRY_SECONDS);
    private static final Logger LOGGER = LoggerFactory.getLogger(ScoringService.class);

    private final ScoringFeed feed;
    private final ScoringStage stage;
    private final ScoringReplay replay;
    private final ScoringWriter writer;
    private final RecentForecastRoutes recentRoutes;
    private final Duration startupWindow;
    private final Clock clock;
    private final ScoringMetrics metrics;
    private final Set<Long> ready = new HashSet<>();
    private final Map<Long, Instant> replayDeferredUntil = new HashMap<>();
    private final Set<Long> stale = ConcurrentHashMap.newKeySet();
    private ForecastTelemetry telemetry = ForecastTelemetry.NONE;

    /**
     * @param feed 정산 스레드의 줄
     * @param stage 정산 단계. 이 서비스의 정산 스레드만 쓴다
     * @param replay 저장된 기록으로 노선 상태를 다시 만드는 복구
     * @param writer 결과를 모아 쓰는 쓰기
     * @param recentRoutes 기동 직후 복구할 노선
     * @param startupWindow 기동 직후 복구할 노선을 고르는 최근 예보 범위. 복구의 예보 범위 상한
     *     ({@link ScoringReplay#maxForecastWindow()})보다 짧으면 그사이에만 예보가 있던 노선은 다음 이벤트가 올 때까지 복구하지 않는다
     * @param clock 복구 기준 시각과 마감 시각
     * @param meters 지표 등록기. 없으면 지표를 내지 않는다
     */
    public ScoringService(
        ScoringFeed feed,
        ScoringStage stage,
        ScoringReplay replay,
        ScoringWriter writer,
        RecentForecastRoutes recentRoutes,
        Duration startupWindow,
        Clock clock,
        MeterRegistry meters
    ) {
        this.feed = Objects.requireNonNull(feed);
        this.stage = Objects.requireNonNull(stage);
        this.replay = Objects.requireNonNull(replay);
        this.writer = Objects.requireNonNull(writer);
        this.recentRoutes = Objects.requireNonNull(recentRoutes);
        this.startupWindow = Objects.requireNonNull(startupWindow);
        this.clock = Objects.requireNonNull(clock);
        this.metrics = new ScoringMetrics(meters, writer);
    }

    /** 새로 저장한 행으로 정산을 마친 예측을 알릴 진단 수신자를 정한다. */
    public void setTelemetry(ForecastTelemetry telemetry) {
        this.telemetry = Objects.requireNonNull(telemetry);
    }

    @Override
    public void committed(ForecastBatchEvents batch) {
        final boolean queued = feed.submit(() -> batch.events().forEach(this::scoreEvent));
        if (queued) {
            return;
        }
        for (ForecastBatchEvents.Event dropped : batch.events()) {
            stale.add(dropped.routeId());
        }
        metrics.dropped(batch.events().size());
    }

    private ScoringResult acceptPasses(List<PassClosed> passes) {
        ScoringResult result = ScoringResult.empty();
        for (PassClosed pass : passes) {
            result = result.plus(stage.accept(pass));
            metrics.lag(Duration.between(pass.closedAt(), clock.instant()));
        }
        return result;
    }

    @Override
    public boolean start() {
        return feed.submit(() -> {
            List<Long> routes = recentRoutes.routesPublishedSince(clock.instant().minus(startupWindow));
            for (final long routeId : routes) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                if (!ready.contains(routeId)) {
                    rebuild(routeId, "startup");
                }
            }
            metrics.waiting(stage.waitingCount());
        });
    }

    /**
     * 이벤트를 버린 노선을 다시 복구하고 오래 배치가 없는 노선의 마감을 닫는 일을 정산 스레드에 넣는다. 주기 작업이 호출한다. 줄이
     * 가득 차면 이번 회차는 건너뛴다. 이벤트를 버린 노선을 다음 이벤트까지 두지 않는 것은, 그 사이 현재 시각 마감이 버린 통과를 기다리던
     * 예측을 놓침으로 잘못 닫지 않게 하려는 것이다.
     *
     * @return 일을 넣었으면 참
     */
    @Override
    public boolean tick() {
        return feed.submit(() -> {
            for (final long routeId : List.copyOf(stale)) {
                if (stale.remove(routeId)) {
                    ready.remove(routeId);
                    rebuild(routeId, "dropped");
                }
            }
            try {
                ScoringResult result = stage.expire(clock.instant());
                bufferResult(result);
            } catch (RuntimeException failure) {
                LOGGER.warn("event=scoring_expire_failed exceptionType={}", failure.getClass().getSimpleName(),
                    failure);
            }
            metrics.waiting(stage.waitingCount());
        });
    }

    /**
     * 모아 둔 결과를 쓴다. 주기 작업이 호출하며 정산 스레드가 아니어도 된다. 실패하면 저장할 행을 남겨 두고 다음 회차에 다시 쓰며 예외를
     * 밖으로 내지 않는다.
     *
     * @return 이번에 새로 넣은 정답, 미해결 기록 수. 실패하면 0
     */
    @Override
    public int flush() {
        try {
            ScoringWriter.Flushed flushed = writer.flush();
            metrics.written(flushed);
            reportClosed(flushed.closed());
            return flushed.labels() + flushed.unresolved();
        } catch (RuntimeException failure) {
            metrics.writeFailure();
            LOGGER.warn(
                    "event=scoring_write_failed pending={} exceptionType={}",
                    writer.pendingCount(),
                    failure.getClass().getSimpleName(),
                    failure
            );
            return 0;
        }
    }

    /** 진단 수신자의 실패는 로그로만 남긴다. 줄은 이미 저장됐다. */
    private void reportClosed(List<ForecastClosed> closed) {
        if (closed.isEmpty()) {
            return;
        }
        try {
            telemetry.closed(closed);
        } catch (RuntimeException failure) {
            LOGGER.warn("event=telemetry_delivery_failed exceptionType={}", failure.getClass().getSimpleName());
        }
    }

    private void scoreEvent(ForecastBatchEvents.Event event) {
        final long routeId = event.routeId();
        try {
            if (!prepare(routeId)) {
                metrics.skipped();
                return;
            }
            ScoringResult result = switch (event) {
                case ObservationBatchSeen seen -> stage.accept(seen);
                case ForecastPublished published -> stage.accept(published);
                case ForecastBatchEvents.ClosedPasses closed -> acceptPasses(closed.passes());
            };
            bufferResult(result);
        } catch (RuntimeException failure) {
            stage.discard(routeId);
            ready.remove(routeId);
            metrics.failure();
            LOGGER.warn(
                    "event=scoring_failed route={} exceptionType={}",
                    routeId,
                    failure.getClass().getSimpleName(),
                    failure
            );
        } finally {
            metrics.waiting(stage.waitingCount());
        }
    }

    private boolean prepare(final long routeId) {
        String reason = rebuildReason(routeId);
        if (reason == null) {
            return true;
        }
        Instant deferred = replayDeferredUntil.get(routeId);
        if (deferred != null && clock.instant().isBefore(deferred)) {
            return false;
        }
        return rebuild(routeId, reason);
    }

    /** 복구해야 하면 그 사유를, 이미 준비된 노선이면 null을 반환한다. 버린 이벤트가 있던 노선은 준비 표시를 지운다. */
    private String rebuildReason(final long routeId) {
        if (stale.remove(routeId)) {
            ready.remove(routeId);
            return "dropped";
        }
        if (ready.contains(routeId)) {
            return null;
        }
        if (replayDeferredUntil.containsKey(routeId)) {
            return "failure";
        }
        return "not_ready";
    }

    private boolean rebuild(final long routeId, String reason) {
        try {
            writer.add(replay.replay(stage, routeId, clock.instant()));
            ready.add(routeId);
            replayDeferredUntil.remove(routeId);
            metrics.replay(reason, true);
            return true;
        } catch (RuntimeException failure) {
            stage.discard(routeId);
            ready.remove(routeId);
            replayDeferredUntil.put(routeId, clock.instant().plus(REPLAY_RETRY));
            metrics.replay(reason, false);
            LOGGER.warn(
                    "event=scoring_replay_failed route={} reason={} exceptionType={}",
                    routeId,
                    reason,
                    failure.getClass().getSimpleName(),
                    failure
            );
            return false;
        }
    }

    private void bufferResult(ScoringResult result) {
        if (result.isEmpty() && result.scored().isEmpty()) {
            return;
        }
        writer.add(result);
        metrics.results(result);
    }
}
