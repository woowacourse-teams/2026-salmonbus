package com.gustler.backend.forecasting.application.pipeline;

import com.gustler.backend.forecasting.api.pipeline.ForecastPublished;
import com.gustler.backend.forecasting.api.pipeline.ObservationBatchSeen;
import com.gustler.backend.forecasting.api.pipeline.PassClosed;
import com.gustler.backend.forecasting.api.pipeline.ScoringFeed;
import com.gustler.backend.forecasting.api.pipeline.ShadowScoringCycle;
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
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 예보가 커밋 뒤에 넘긴 사건을 채점 단계({@link ScoringStage})에 차례로 흘리고 결과를 {@link ScoringWriter}에 모으는 채점이다.
 *
 * <p>사건을 받는 쪽({@link PipelineEvents})은 일을 {@link ScoringFeed}에 넣기만 한다. 채점 단계는 동시 호출을 지원하지 않으므로
 * 채점 단계와 노선 준비 표시는 그 줄의 전용 스레드에서만 만진다. 노선마다 다음 규칙을 지킨다.
 * <ul>
 *   <li>노선의 사건을 처음 처리하기 전에 {@link ScoringReplay}로 저장된 기록을 되감아 상태를 만든다. 기동 직후에는
 *       {@link #start()}가 되감기의 예보 범위 상한 안에 예보가 있던 노선을 모두 미리 되감는다. 내려가 있던 동안 마감이 지난
 *       예측은 그 뒤 사건이 오지 않는 노선에서도 이 되감기와 {@link #tick()}의 마감으로 닫힌다. 사건은 커밋 뒤에만 오므로
 *       되감기가 그 사건을 이미 읽었을 수 있으나, 채점 단계는 같은 사건을 다시 받아도 결과 집합이 같다.</li>
 *   <li>줄이 가득 차 사건을 버렸으면 그 노선을 다시 되감아야 한다고 표시하고, 다음 일에서 상태를 버리고 되감는다.</li>
 *   <li>처리 중 예외가 나면 그 노선의 상태를 버린다({@link ScoringStage#discard}). 다음 사건이 다시 되감는다.</li>
 *   <li>되감기가 실패하면 {@value #REPLAY_RETRY_SECONDS}초 동안 그 노선의 사건을 처리하지 않고 건너뛴다. 되감지 못한 상태로
 *       처리하면 직전 통과가 빠진 정답이 저장되어 고칠 수 없기 때문이다. 건너뛴 사건은 저장돼 있어 다음 되감기가 읽는다.
 *       되감기는 채점이 그 노선을 저장까지 마친 사건 시각부터 예보를 다시 읽으므로, 실패가 오래 이어져도 그사이 닫힌 통과의
 *       정답이 빠지지 않는다.</li>
 * </ul>
 * 결과는 {@link #flush()}가 몇 초마다 쓰고, {@link #tick()}이 채점 스레드에서 {@link ScoringStage#expire}를 불러 오래 배치가
 * 없는 노선의 마감을 닫는다. 내려가기 전에는 {@link #stopAccepting()}으로 사건을 더 받지 않게 하고, 채점 줄을 비운 뒤
 * {@link #flushRemaining()}으로 남은 결과를 쓴다. {@code route} 행 잠금을 잡지 않는다.
 */
public final class ShadowScoringService implements PipelineEvents, ShadowScoringCycle {
    static final long REPLAY_RETRY_SECONDS = 30;
    private static final Duration REPLAY_RETRY = Duration.ofSeconds(REPLAY_RETRY_SECONDS);
    private static final Logger LOGGER = LoggerFactory.getLogger(ShadowScoringService.class);

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
    private volatile boolean stopped;

    /**
     * @param feed 채점 스레드의 줄
     * @param stage 채점 단계. 이 서비스의 채점 스레드만 쓴다
     * @param replay 저장된 기록으로 노선 상태를 다시 만드는 되감기
     * @param writer 결과를 모아 쓰는 쓰기
     * @param recentRoutes 기동 직후 되감을 노선
     * @param startupWindow 기동 직후 되감을 노선을 고르는 최근 예보 범위. 되감기의 예보 범위 상한
     *     ({@link ScoringReplay#maxForecastWindow()})보다 짧으면 그사이에만 예보가 있던 노선은 다음 사건이 올 때까지 되감지 않는다
     * @param clock 되감기 기준 시각과 마감 시각
     * @param meters 지표 등록기. 없으면 지표를 내지 않는다
     */
    public ShadowScoringService(
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

    @Override
    public void batchSeen(ObservationBatchSeen seen) {
        submit(seen.routeId(), () -> stage.accept(seen));
    }

    @Override
    public void forecastPublished(ForecastPublished published) {
        submit(published.routeId(), () -> stage.accept(published));
    }

    @Override
    public void passesClosed(List<PassClosed> passes) {
        if (passes.isEmpty()) {
            return;
        }
        List<PassClosed> copied = List.copyOf(passes);
        final long routeId = copied.getFirst().routeId();
        if (copied.stream().anyMatch(pass -> pass.routeId() != routeId)) {
            throw new IllegalArgumentException("한 번에 넘기는 통과는 같은 노선이어야 한다");
        }
        submit(routeId, () -> {
            ScoringResult result = ScoringResult.empty();
            for (PassClosed pass : copied) {
                result = result.plus(stage.accept(pass));
                metrics.lag(Duration.between(pass.closedAt(), clock.instant()));
            }
            return result;
        });
    }

    @Override
    public boolean start() {
        if (stopped) {
            return false;
        }
        return feed.submit(() -> {
            List<Long> routes = recentRoutes.routesPublishedSince(clock.instant().minus(startupWindow));
            for (final long routeId : routes) {
                if (stopped) {
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
     * 사건을 버린 노선을 다시 되감고 오래 배치가 없는 노선의 마감을 닫는 일을 채점 스레드에 넣는다. 주기 작업이 부른다. 줄이
     * 가득 차면 이번 회차는 건너뛴다. 사건을 버린 노선을 다음 사건까지 두지 않는 것은, 그 사이 벽시계 마감이 버린 통과를 기다리던
     * 예측을 놓침으로 잘못 닫지 않게 하려는 것이다.
     *
     * @return 일을 넣었으면 참
     */
    @Override
    public boolean tick() {
        if (stopped) {
            return false;
        }
        return feed.submit(() -> {
            for (final long routeId : List.copyOf(stale)) {
                if (stale.remove(routeId)) {
                    ready.remove(routeId);
                    rebuild(routeId, "dropped");
                }
            }
            try {
                ScoringResult result = stage.expire(clock.instant());
                add(result);
            } catch (RuntimeException failure) {
                LOGGER.warn("event=pipeline_scoring_expire_failed exceptionType={}", failure.getClass().getSimpleName(),
                    failure);
            }
            metrics.waiting(stage.waitingCount());
        });
    }

    /**
     * 모아 둔 결과를 쓴다. 주기 작업이 부르며 채점 스레드가 아니어도 된다. 실패하면 줄을 남겨 두고 다음 회차에 다시 쓰며 예외를
     * 밖으로 내지 않는다.
     *
     * @return 이번에 새로 넣은 정답·미해결 줄 수. 실패하면 0
     */
    @Override
    public int flush() {
        try {
            ScoringWriter.Flushed flushed = writer.flush();
            metrics.written(flushed);
            return flushed.labels() + flushed.unresolved();
        } catch (RuntimeException failure) {
            metrics.writeFailure();
            LOGGER.warn("event=pipeline_scoring_write_failed pending={} exceptionType={}", writer.pendingCount(),
                failure.getClass().getSimpleName(), failure);
            return 0;
        }
    }

    @Override
    public void stopAccepting() {
        stopped = true;
    }

    /**
     * 모아 둔 결과를 지금 쓰고 남은 줄 수를 돌려준다. 주기 쓰기가 도는 중이면 그것이 끝난 뒤에 쓴다. 받기를 멈추지는 않으므로
     * 채점 스레드가 이 호출 뒤에 낸 결과는 들어가지 않는다.
     *
     * @return 쓰지 못하고 남은 줄 수
     */
    @Override
    public int flushRemaining() {
        final int written = flush();
        final int remaining = writer.pendingCount();
        LOGGER.info("event=pipeline_scoring_final_write written={} remaining={}", written, remaining);
        return remaining;
    }

    private void submit(final long routeId, Supplier<ScoringResult> work) {
        if (stopped) {
            return;
        }
        if (!feed.submit(() -> process(routeId, work))) {
            stale.add(routeId);
            metrics.dropped();
        }
    }

    private void process(final long routeId, Supplier<ScoringResult> work) {
        try {
            if (!prepare(routeId)) {
                metrics.skipped();
                return;
            }
            add(work.get());
        } catch (RuntimeException failure) {
            stage.discard(routeId);
            ready.remove(routeId);
            metrics.failure();
            LOGGER.warn("event=pipeline_scoring_failed route={} exceptionType={}", routeId,
                failure.getClass().getSimpleName(), failure);
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

    /** 되감아야 하면 그 사유를, 이미 준비된 노선이면 null을 돌려준다. 버린 사건이 있던 노선은 준비 표시를 지운다. */
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
            LOGGER.warn("event=pipeline_scoring_replay_failed route={} reason={} exceptionType={}", routeId, reason,
                failure.getClass().getSimpleName(), failure);
            return false;
        }
    }

    private void add(ScoringResult result) {
        if (result.isEmpty() && result.scored().isEmpty()) {
            return;
        }
        writer.add(result);
        metrics.results(result);
    }
}
