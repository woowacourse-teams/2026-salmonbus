package com.gustler.backend.worker.scheduling;

import com.gustler.backend.forecasting.api.pipeline.ForecastLanes;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

/**
 * 본선({@code forecast.pipeline=v2})의 노선별 실행 줄이다. 단일 스레드 실행기({@code forecast-lane-<번호>-})를
 * {@code forecast.lanes}개 두고, 노선 열쇠의 해시로 줄 하나에 고정 배정한다.
 *
 * <p>같은 열쇠의 일은 항상 같은 줄에서 넣은 순서대로 하나씩 실행되고, 다른 줄의 일은 동시에 실행된다. 스케줄러의 풀 크기를 올리는
 * 방식과 달리 같은 노선이 두 스레드에서 동시에 처리되지 않는다. 줄마다 길이 제한이 있고, 가득 차면 넣지 않고 거짓을 돌려준다
 * (다음 줍기가 다시 넣는다). 일 하나가 실패해도 그 줄은 다음 일을 계속 실행한다. 줄 길이는
 * {@code salmonbus.pipeline.forecast.queue{lane}} 게이지로 낸다. 종료할 때 기다리는 일은 버린다.
 */
public class RouteLanes implements ForecastLanes, DisposableBean {
    /** 실행 줄 스레드 이름의 앞부분. */
    public static final String THREAD_PREFIX = "forecast-lane-";
    private static final Logger LOGGER = LoggerFactory.getLogger(RouteLanes.class);

    private final List<ThreadPoolExecutor> lanes;

    /**
     * @param count 줄 수
     * @param capacity 줄 하나에 기다릴 수 있는 일의 수
     * @param meters 지표 등록기. 없으면 줄 길이 지표를 내지 않는다
     */
    public RouteLanes(final int count, final int capacity, MeterRegistry meters) {
        if (count < 1 || capacity < 1) {
            throw new IllegalArgumentException("줄 수와 줄 길이는 1 이상이어야 한다");
        }
        List<ThreadPoolExecutor> created = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            String prefix = THREAD_PREFIX + index + "-";
            ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), runnable -> {
                    Thread thread = new Thread(runnable, prefix + "1");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
            created.add(executor);
            if (meters != null) {
                Gauge.builder("salmonbus.pipeline.forecast.queue", executor, pool -> pool.getQueue().size())
                    .tag("lane", String.valueOf(index)).strongReference(true).register(meters);
            }
        }
        this.lanes = List.copyOf(created);
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

    /** 노선 열쇠가 배정되는 줄 번호. */
    public int laneOf(String routeKey) {
        return Math.floorMod(routeKey.hashCode(), lanes.size());
    }

    /** 줄 수. */
    public int count() {
        return lanes.size();
    }

    /** 지금 모든 줄에서 기다리는 일의 수. */
    public int queued() {
        return lanes.stream().mapToInt(lane -> lane.getQueue().size()).sum();
    }

    @Override
    public void destroy() {
        lanes.forEach(ThreadPoolExecutor::shutdownNow);
    }

    private static void runSafely(String routeKey, Runnable task) {
        try {
            task.run();
        } catch (RuntimeException failure) {
            LOGGER.warn("event=pipeline_forecast_task_failed routeKey={} exceptionType={}", routeKey,
                failure.getClass().getSimpleName(), failure);
        }
    }
}
