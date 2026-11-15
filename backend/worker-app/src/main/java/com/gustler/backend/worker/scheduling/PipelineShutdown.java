package com.gustler.backend.worker.scheduling;

import com.gustler.backend.forecasting.api.pipeline.ShadowScoringCycle;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 정상 종료 때 새 예보 경로가 받은 일을 끝까지 처리하고 내려가게 한다. 컨텍스트가 닫힐 때 다음 순서로 한 번 돈다.
 *
 * <ol>
 *   <li>수집 스케줄러의 남은 예약을 취소하고, 수집과 줍기 스케줄러가 돌던 회차를 끝내기를 기다린다. 채점 주기 작업도 더 돌지 않게
 *       한다. 이 대기는 한도의 절반까지만 한다. 기다리는 동안 실행 줄은 그 회차가 넘긴 배치를 계속 받는다.</li>
 *   <li>예보 실행 줄이 새 일을 받지 않게 하고 받은 배치를 모두 처리하기를 기다린다.</li>
 *   <li>채점이 사건을 더 받지 않게 하고 채점 줄이 받은 사건을 모두 처리하기를 기다린다.</li>
 *   <li>채점이 모아 둔 결과를 한 번 쓴다.</li>
 * </ol>
 * 1~3은 합쳐서 {@code forecast.shutdown-timeout}까지만 기다린다. 한도가 지나면 남은 일을 버리고
 * {@code event=pipeline_shutdown_abandoned} 한 줄에 무엇을 얼마나 버렸는지 남긴다. 버린 배치는 다시 뜬 뒤 줍기가, 버린 사건과 쓰지
 * 못한 결과는 채점 되감기가 다시 만든다. 마지막 쓰기는 별도 스레드에서 돌리고 남은 한도(적어도
 * {@link #FINAL_WRITE_GRACE})만 기다린다. 한 단계가 예외로 끝나도 다음 단계를 한다.
 *
 * <p>스프링은 컨텍스트를 닫을 때 {@link SmartLifecycle}을 단계가 큰 것부터 모두 멈춘 뒤에 빈을 파괴한다. DB 연결 풀은 빈 파괴에서
 * 닫히므로 위 순서는 풀이 닫히기 전에 끝난다. 단계({@link #PHASE})는 웹 서버의 요청 마감과 스케줄러 정지보다 커서 그보다 먼저 돈다.
 * 스케줄러 정지는 다른 주기 작업이 돌던 회차를 스프링의 단계별 종료 시한까지 기다리므로, 그 뒤에 두면 줄을 비울 시간이 남지 않을 수
 * 있다.
 *
 * <p>한 번 멈추면 실행 줄이 닫혀 다시 시작할 수 없다. 그래서 컨텍스트 일시 정지에는 참여하지 않는다.
 */
public final class PipelineShutdown implements SmartLifecycle {
    /** 웹 서버의 요청 마감({@code DEFAULT_PHASE - 1024})과 스케줄러 정지({@code Integer.MAX_VALUE / 2})보다 먼저 멈춘다. */
    public static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 512;
    /** 줄을 비우는 데 한도를 다 썼어도 마지막 쓰기를 기다려 주는 시간. */
    static final Duration FINAL_WRITE_GRACE = Duration.ofSeconds(2);
    private static final String FINAL_WRITE_THREAD = "pipeline-shutdown-final-write";
    private static final int UNKNOWN = -1;
    private static final String FIELDS = " elapsedMs={} timeoutMs={} intakeRunning={} forecastQueued={}"
        + " forecastRunning={} scoringQueued={} scoringRunning={} unwritten={} failedStages={}";
    private static final Logger LOGGER = LoggerFactory.getLogger(PipelineShutdown.class);

    private final Parts parts;
    private final Duration timeout;
    private final Duration finalWriteGrace;
    private final AtomicBoolean running = new AtomicBoolean();

    /**
     * @param parts 종료 때 다루는 구성 요소
     * @param timeout 수집 대기와 줄 비우기를 합친 한도
     */
    public PipelineShutdown(Parts parts, Duration timeout) {
        this(parts, timeout, FINAL_WRITE_GRACE);
    }

    PipelineShutdown(Parts parts, Duration timeout, Duration finalWriteGrace) {
        this.parts = Objects.requireNonNull(parts);
        this.timeout = Objects.requireNonNull(timeout);
        this.finalWriteGrace = Objects.requireNonNull(finalWriteGrace);
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("종료 한도는 0 이상이어야 한다: " + timeout);
        }
    }

    @Override
    public void start() {
        running.set(true);
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        Deadline intake = Deadline.after(timeout.dividedBy(2));
        Deadline deadline = Deadline.after(timeout);
        List<String> failed = new ArrayList<>();
        final int intakeRunning = attempt("intake", failed, () -> stopIntake(intake), 0);
        QueueDrain forecast = attempt("forecast", failed, () -> drain(parts.forecast(), deadline),
            QueueDrain.COMPLETE);
        attempt("scoring_stop", failed, () -> parts.cycles().forEach(ShadowScoringCycle::stopAccepting));
        QueueDrain scoring = attempt("scoring", failed, () -> drain(parts.scoring(), deadline), QueueDrain.COMPLETE);
        final int unwritten = attempt("final_write", failed, () -> writeRemaining(deadline), UNKNOWN);
        report(new Outcome(intakeRunning, forecast, scoring, unwritten, failed), deadline.elapsed());
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    @Override
    public boolean isPauseable() {
        return false;
    }

    /** 단계 하나를 실행한다. 예외로 끝나면 로그를 남기고 대신할 값을 돌려줘 다음 단계가 돌게 한다. */
    private static <T> T attempt(String stage, List<String> failed, Supplier<T> step, T fallback) {
        try {
            return step.get();
        } catch (RuntimeException failure) {
            failed.add(stage);
            LOGGER.warn("event=pipeline_shutdown_stage_failed stage={} exceptionType={}", stage,
                failure.getClass().getSimpleName(), failure);
            return fallback;
        }
    }

    private static void attempt(String stage, List<String> failed, Runnable step) {
        attempt(stage, failed, () -> {
            step.run();
            return true;
        }, false);
    }

    /**
     * 수집과 줍기가 새 회차를 시작하지 않게 하고 돌던 회차가 끝나기를 기다린다. 줍기 스케줄러에 이미 들어가 있는 일은 취소하지 않고
     * 끝나기를 기다린다. 본선이 옛 경로일 때 그 일은 옛 예보 한 건이고 그림자 실행은 그 뒤에 일을 받는다.
     *
     * @return 마감까지 끝나지 않은 스케줄러 수
     */
    private int stopIntake(Deadline deadline) {
        parts.collection().forEach(PipelineShutdown::cancelSchedule);
        parts.pickup().forEach(ThreadPoolTaskScheduler::initiateShutdown);
        parts.scoringTicks().forEach(ThreadPoolTaskScheduler::initiateShutdown);
        int stillRunning = 0;
        for (ThreadPoolTaskScheduler scheduler : parts.intake()) {
            if (!deadline.awaitsTermination(scheduler.getScheduledThreadPoolExecutor())) {
                stillRunning++;
            }
        }
        return stillRunning;
    }

    /**
     * 스케줄러를 멈추고 예약만 돼 있는 실행을 취소한다. 스프링은 컨텍스트가 닫히기 시작할 때 스케줄러에 새 일을 넣지 못하게 하지만,
     * 다음 수집처럼 실행 시각을 기다리는 한 번짜리 예약은 남겨 두어 종료 중에도 실행된다.
     */
    private static void cancelSchedule(ThreadPoolTaskScheduler scheduler) {
        scheduler.initiateShutdown();
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    private static QueueDrain drain(List<DrainableQueue> queues, Deadline deadline) {
        QueueDrain total = QueueDrain.COMPLETE;
        for (DrainableQueue queue : queues) {
            total = total.plus(queue.drain(deadline.remaining()));
        }
        return total;
    }

    /**
     * 채점이 모아 둔 결과를 쓴다. 쓰기가 DB를 기다리다 한도를 넘기지 않게 별도 스레드에서 돌리고, 남은 한도와 여유 시간 가운데 긴
     * 쪽만 기다린다.
     *
     * @return 쓰지 못하고 남은 줄 수. 기다리는 동안 쓰기가 끝나지 않았으면 {@code UNKNOWN}
     */
    private int writeRemaining(Deadline deadline) {
        if (parts.cycles().isEmpty()) {
            return 0;
        }
        FutureTask<Integer> write = new FutureTask<>(this::writeAll);
        Thread thread = new Thread(write, FINAL_WRITE_THREAD);
        thread.setDaemon(true);
        thread.start();
        try {
            return write.get(longer(deadline.remaining(), finalWriteGrace).toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException late) {
            write.cancel(true);
            return UNKNOWN;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            write.cancel(true);
            return UNKNOWN;
        } catch (ExecutionException failure) {
            throw new IllegalStateException("마지막 쓰기가 실패했다", failure.getCause());
        }
    }

    private int writeAll() {
        int remaining = 0;
        for (ShadowScoringCycle cycle : parts.cycles()) {
            remaining += writeWithRetry(cycle);
        }
        return remaining;
    }

    /** 쓰기가 실패해 줄이 남으면 한 번 더 쓴다. */
    private static int writeWithRetry(ShadowScoringCycle cycle) {
        if (cycle.flushRemaining() == 0) {
            return 0;
        }
        return cycle.flushRemaining();
    }

    private static Duration longer(Duration left, Duration right) {
        if (left.compareTo(right) >= 0) {
            return left;
        }
        return right;
    }

    private void report(Outcome outcome, Duration elapsed) {
        Object[] values = {
            elapsed.toMillis(), timeout.toMillis(), outcome.intakeRunning(), outcome.forecast().queued(),
            outcome.forecast().running(), outcome.scoring().queued(), outcome.scoring().running(),
            describe(outcome.unwritten()), String.join(",", outcome.failed())
        };
        if (outcome.complete()) {
            LOGGER.info("event=pipeline_shutdown_drained" + FIELDS, values);
            return;
        }
        LOGGER.warn("event=pipeline_shutdown_abandoned" + FIELDS, values);
    }

    private static String describe(final int unwritten) {
        if (unwritten == UNKNOWN) {
            return "unknown";
        }
        return String.valueOf(unwritten);
    }

    /**
     * 종료 때 다루는 구성 요소다. 스위치 조합에 따라 없는 것은 빈 목록이고, 그 단계는 아무 일도 하지 않는다.
     *
     * @param collection 수집 스케줄러. 남은 예약을 취소하고 돌던 회차를 기다린다
     * @param pickup 줍기와 옛 예보가 도는 스케줄러. 들어가 있는 일이 끝나기를 기다린다
     * @param forecast 예보 실행 줄. 본선의 노선별 실행 줄이나 그림자 실행 줄이다
     * @param scoring 채점 줄
     * @param scoringTicks 채점 주기 작업의 스케줄러
     * @param cycles 채점. 사건 받기를 멈추고 마지막 쓰기를 한다
     */
    public record Parts(
        List<ThreadPoolTaskScheduler> collection,
        List<ThreadPoolTaskScheduler> pickup,
        List<DrainableQueue> forecast,
        List<DrainableQueue> scoring,
        List<ThreadPoolTaskScheduler> scoringTicks,
        List<ShadowScoringCycle> cycles
    ) {
        public Parts {
            collection = List.copyOf(collection);
            pickup = List.copyOf(pickup);
            forecast = List.copyOf(forecast);
            scoring = List.copyOf(scoring);
            scoringTicks = List.copyOf(scoringTicks);
            cycles = List.copyOf(cycles);
        }

        /** 실행 줄에 일을 넣는 스케줄러. 수집이 먼저다. */
        List<ThreadPoolTaskScheduler> intake() {
            List<ThreadPoolTaskScheduler> schedulers = new ArrayList<>(collection);
            schedulers.addAll(pickup);
            return schedulers;
        }
    }

    /**
     * @param intakeRunning 한도 안에 회차를 끝내지 못한 수집·줍기 스케줄러 수
     * @param unwritten 쓰지 못하고 남은 채점 결과 줄 수. 쓰기가 끝나지 않아 모르면 {@code UNKNOWN}
     * @param failed 예외로 끝난 단계
     */
    private record Outcome(
        int intakeRunning,
        QueueDrain forecast,
        QueueDrain scoring,
        int unwritten,
        List<String> failed
    ) {
        boolean complete() {
            return intakeRunning == 0 && forecast.complete() && scoring.complete() && unwritten == 0
                && failed.isEmpty();
        }
    }
}
