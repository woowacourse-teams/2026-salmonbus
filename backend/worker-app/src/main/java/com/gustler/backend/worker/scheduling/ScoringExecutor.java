package com.gustler.backend.worker.scheduling;

import com.gustler.backend.forecasting.api.forecast.ScoringFeed;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * 정산 작업을 전용 단일 스레드({@code forecast-scoring-})에서 등록 순서대로 실행한다.
 *
 * <p>예보 실행기와 주기 작업은 {@link #submit}에서 용량이 제한된 큐에 등록만 하고 기다리지 않는다.
 * 큐가 가득 차면 거짓을 반환한다. 작업 하나가 실패해도 다음 작업을 계속 실행한다.
 * 큐 길이는 {@code salmonbus.scoring.queue} 게이지로 제공한다.
 *
 * <p>종료 시 예보 실행기({@link RouteLanes})가 멈춘 직후 {@link #stop()}을 실행해 마지막 예보 배치의 이벤트까지 받는다.
 * 이후 새 작업은 거절하고 대기 작업은 제거한다. 실행 중인 작업에는 중단을 요청하고 제한 시간까지 기다린다.
 * 정산 스레드는 DB를 읽기만 하므로 중단돼도 일부만 저장한 행이 남지 않는다.
 * 제거한 이벤트는 저장된 기록에서 복구할 수 있으므로 재시작 후 정산 복구에서 다시 처리한다.
 * 한 번 종료한 실행기는 재시작할 수 없으므로 컨텍스트 일시 정지에는 참여하지 않는다.
 */
public class ScoringExecutor implements ScoringFeed, SmartLifecycle {

    /** 정산 스레드 이름의 앞부분. */
    public static final String THREAD_PREFIX = "forecast-scoring-";
    /** worker의 정산 큐 용량이다. 큐가 가득 차 이벤트를 버리면 해당 노선을 다시 복구한다. */
    public static final int QUEUE_CAPACITY = 10_000;
    /** worker가 닫을 때 돌던 일이 끝나기를 기다리는 한도. */
    public static final Duration STOP_WAIT = Duration.ofSeconds(5);
    /** 예보 실행기가 멈춘 다음 단계다. */
    private static final int PHASE = RouteLanes.PHASE - 1;
    private static final Logger LOGGER = LoggerFactory.getLogger(ScoringExecutor.class);

    private final ThreadPoolExecutor executor;
    private final Duration stopWait;

    /**
     * @param capacity 큐에서 기다릴 수 있는 일의 수
     * @param stopWait 닫을 때 돌던 일이 끝나기를 기다리는 한도
     * @param meters 지표 등록기. 없으면 큐 길이 지표를 내지 않는다
     */
    public ScoringExecutor(final int capacity, Duration stopWait, MeterRegistry meters) {
        AtomicInteger sequence = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity),
                runnable -> {
                    Thread thread = new Thread(runnable, THREAD_PREFIX + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
        this.stopWait = stopWait;
        if (meters != null) {
            Gauge.builder("salmonbus.scoring.queue", executor, pool -> pool.getQueue().size())
                .strongReference(true).register(meters);
        }
    }

    @Override
    public boolean submit(Runnable task) {
        try {
            executor.execute(() -> runSafely(task));
            return true;
        } catch (RejectedExecutionException full) {
            return false;
        }
    }

    private static void runSafely(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException failure) {
            LOGGER.warn("event=scoring_task_failed exceptionType={}", failure.getClass().getSimpleName());
        }
    }

    /** 큐에서 실행을 기다리는 작업 수다. */
    public int queued() {
        return executor.getQueue().size();
    }

    /** 일하는 스레드는 첫 일이 들어올 때 만들어지므로 시작할 것이 없다. */
    @Override
    public void start() {
    }

    /** 실행기를 종료한다. 닫은 뒤 {@link #submit}은 거짓을 반환한다. 한도 안에 돌던 일이 끝나지 않으면 경고를 남기고 돌아온다. */
    @Override
    public void stop() {
        final int discarded = executor.shutdownNow().size();
        if (!terminates()) {
            LOGGER.warn("event=scoring_stop_timeout discarded={} waitMs={}", discarded, stopWait.toMillis());
            return;
        }
        LOGGER.info("event=scoring_stopped discarded={}", discarded);
    }

    private boolean terminates() {
        try {
            return executor.awaitTermination(stopWait.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return executor.isTerminated();
        }
    }

    /** 닫기 전에는 참이다. 스프링은 참일 때만 {@link #stop()}을 호출한다. */
    @Override
    public boolean isRunning() {
        return !executor.isShutdown();
    }

    @Override
    public boolean isPauseable() {
        return false;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
