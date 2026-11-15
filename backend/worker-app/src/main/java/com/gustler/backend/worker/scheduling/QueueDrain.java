package com.gustler.backend.worker.scheduling;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 실행 줄을 비우다가 한도 때문에 끝내지 못한 일의 수다.
 *
 * @param queued 시작하지 못해 버린 일의 수
 * @param running 실행 중이어서 중단을 요청한 일의 수
 */
public record QueueDrain(int queued, int running) {
    /** 받은 일을 모두 끝냈다. */
    public static final QueueDrain COMPLETE = new QueueDrain(0, 0);

    /**
     * 실행기들이 새 일을 받지 않게 한 뒤 받은 일이 모두 끝나기를 한도까지 기다린다. 한도가 지나면 기다리던 일을 버리고 실행 중인 일에
     * 중단을 요청한다. 한도는 실행기 전체에 한 번 적용한다.
     */
    static QueueDrain of(List<ThreadPoolExecutor> executors, Duration timeout) {
        executors.forEach(ThreadPoolExecutor::shutdown);
        Deadline deadline = Deadline.after(timeout);
        QueueDrain total = COMPLETE;
        for (ThreadPoolExecutor executor : executors) {
            total = total.plus(finish(executor, deadline));
        }
        return total;
    }

    private static QueueDrain finish(ThreadPoolExecutor executor, Deadline deadline) {
        if (deadline.awaitsTermination(executor)) {
            return COMPLETE;
        }
        final int running = executor.getActiveCount();
        return new QueueDrain(executor.shutdownNow().size(), running);
    }

    public boolean complete() {
        return queued == 0 && running == 0;
    }

    public QueueDrain plus(QueueDrain other) {
        return new QueueDrain(queued + other.queued, running + other.running);
    }
}
