package com.gustler.backend.worker.scheduling;

import com.gustler.backend.forecasting.api.forecast.ForecastLanes;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ExecutorConfigurationSupport;

/**
 * 예보 작업을 노선별 단일 스레드 실행기에 배정한다.
 * 실행기는 {@code forecast.lanes}개이며 스레드 이름은 {@code forecast-lane-<번호>-}다.
 * GBIS 노선 키의 해시로 실행기를 정하므로 같은 노선은 등록 순서대로 처리하고 다른 실행기는 병렬로 처리한다.
 * 같은 노선을 두 스레드에서 동시에 처리하지 않는다.
 *
 * <p>실행기마다 용량이 제한된 큐가 있다. 큐가 가득 차면 등록하지 않고 거짓을 반환하며 다음 주기 조회에서 다시 시도한다.
 * 작업 하나가 실패해도 다음 작업은 계속 실행한다. 큐 길이는 {@code salmonbus.forecast.queue{lane}} 게이지로 제공한다.
 *
 * <p>종료 시 수집과 주기 조회 스케줄러가 현재 회차를 마친 뒤 {@link #stop()}을 실행한다.
 * 그 회차에서 전달한 배치까지 받은 뒤 새 작업은 거절하고 대기 중인 작업은 제거한다.
 * 실행 중인 작업에는 중단을 요청하지 않고 제한 시간까지 기다린다. 작업은 {@link #closing()}을 확인해 현재 배치 하나만 마친다.
 * 스프링은 수명 주기 빈을 정지한 뒤 파괴하므로 정상 종료 시 DB 연결 풀이 닫히기 전에 작업을 마친다.
 * 제거한 작업의 배치는 재시작 후 주기 조회에서 다시 읽는다.
 * 한 번 종료한 실행기는 재시작할 수 없으므로 컨텍스트 일시 정지에는 참여하지 않는다.
 */
public class RouteLanes implements ForecastLanes, SmartLifecycle {

    /** 실행기 스레드 이름의 앞부분. */
    public static final String THREAD_PREFIX = "forecast-lane-";
    /** 실행기마다 대기할 수 있는 최대 작업 수. */
    public static final int QUEUE_CAPACITY = 1_000;
    /**
     * worker가 닫을 때 돌던 일이 끝나기를 기다리는 한도. 스프링이 스케줄러의 돌던 회차를 기다리는 시한(기본 30초)과 정산 큐의 한도를
     * 더해도 서비스 유닛의 강제 종료 시한(45초)보다 짧아야 한다.
     */
    public static final Duration STOP_WAIT = Duration.ofSeconds(5);
    /** 스프링의 스케줄러가 돌던 회차를 끝내는 단계의 바로 다음 단계다. 단계가 큰 것부터 멈춘다. */
    static final int PHASE = ExecutorConfigurationSupport.DEFAULT_PHASE - 1;
    private static final Logger LOGGER = LoggerFactory.getLogger(RouteLanes.class);

    private final List<ThreadPoolExecutor> lanes;
    private final Duration stopWait;

    /**
     * @param count 실행기 수
     * @param capacity 실행기마다 대기할 수 있는 최대 작업 수
     * @param stopWait 닫을 때 돌던 일이 끝나기를 기다리는 한도. 전체 실행기에 한 번 적용한다
     * @param meters 지표 등록기. 없으면 큐 길이 지표를 내지 않는다
     */
    public RouteLanes(
        final int count,
        final int capacity,
        Duration stopWait,
        MeterRegistry meters
    ) {
        if (count < 1 || capacity < 1) {
            throw new IllegalArgumentException("실행기 수와 큐 용량은 1 이상이어야 한다");
        }
        List<ThreadPoolExecutor> created = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            ThreadPoolExecutor executor = createLane(index, capacity);
            created.add(executor);
            if (meters != null) {
                registerQueueGauge(meters, executor, index);
            }
        }
        this.lanes = List.copyOf(created);
        this.stopWait = stopWait;
    }

    private static ThreadPoolExecutor createLane(final int index, final int capacity) {
        String prefix = THREAD_PREFIX + index + "-";
        return new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity),
                runnable -> {
                    Thread thread = new Thread(runnable, prefix + "1");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    private static void registerQueueGauge(MeterRegistry meters, ThreadPoolExecutor executor, final int index) {
        Gauge.builder("salmonbus.forecast.queue", executor, pool -> pool.getQueue().size())
            .tag("lane", String.valueOf(index))
            .strongReference(true)
            .register(meters);
    }

    @Override
    public boolean submit(String routeKey, Runnable task) {
        try {
            lanes.get(laneOf(routeKey)).execute(() -> runSafely(routeKey, task));
            return true;
        } catch (RejectedExecutionException full) {
            return false;
        }
    }

    private static void runSafely(String routeKey, Runnable task) {
        try {
            task.run();
        } catch (RuntimeException failure) {
            LOGGER.warn(
                    "event=forecast_task_failed routeKey={} exceptionType={}",
                    routeKey,
                    failure.getClass().getSimpleName()
            );
        }
    }

    /** 닫기 시작한 뒤로 참이다. 실행기를 종료하는 스레드가 돌던 일을 기다리는 동안에도 참이다. */
    @Override
    public boolean closing() {
        return lanes.getFirst().isShutdown();
    }

    /** 노선 키가 배정되는 실행기 번호. */
    public int laneOf(String routeKey) {
        return Math.floorMod(routeKey.hashCode(), lanes.size());
    }

    /** 실행기 수. */
    public int count() {
        return lanes.size();
    }

    /** 모든 실행기의 큐에서 대기 중인 작업 수. */
    public int queued() {
        return lanes.stream().mapToInt(lane -> lane.getQueue().size()).sum();
    }

    /** 일하는 스레드는 첫 일이 들어올 때 만들어지므로 시작할 것이 없다. */
    @Override
    public void start() {
    }

    /**
     * 모든 실행기를 종료한다. 닫은 뒤 {@link #submit}은 거짓을 반환한다. 한도 안에 돌던 일이 끝나지 않으면 경고를 남기고 돌아온다. 그
     * 일은 DB 연결 풀이 닫힌 뒤 실패로 끝날 수 있으며, 트랜잭션이 통째로 되돌려지므로 일부만 저장한 행은 남지 않는다.
     */
    @Override
    public void stop() {
        int discarded = 0;
        for (ThreadPoolExecutor lane : lanes) {
            lane.shutdown();
            discarded += lane.getQueue().drainTo(new ArrayList<>());
        }
        final long deadline = System.nanoTime() + stopWait.toNanos();
        int running = 0;
        for (ThreadPoolExecutor lane : lanes) {
            if (!terminatesBy(lane, deadline)) {
                running++;
            }
        }
        if (running > 0) {
            LOGGER.warn(
                    "event=forecast_stop_timeout discarded={} running={} waitMs={}",
                    discarded,
                    running,
                    stopWait.toMillis()
            );
            return;
        }
        LOGGER.info("event=forecast_stopped discarded={}", discarded);
    }

    private static boolean terminatesBy(ThreadPoolExecutor lane, final long deadline) {
        try {
            return lane.awaitTermination(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return lane.isTerminated();
        }
    }

    /** 닫기 전에는 참이다. 스프링은 참일 때만 {@link #stop()}을 호출한다. */
    @Override
    public boolean isRunning() {
        return !lanes.getFirst().isShutdown();
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
