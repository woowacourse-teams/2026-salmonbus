package com.gustler.backend.worker.scheduling;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 지금부터 정한 시간 뒤의 마감이다. 벽시계가 바뀌어도 흔들리지 않게 {@link System#nanoTime()}으로 잰다.
 *
 * @param startedNanos 재기 시작한 시각
 * @param timeoutNanos 마감까지의 길이
 */
record Deadline(long startedNanos, long timeoutNanos) {
    static Deadline after(Duration timeout) {
        return new Deadline(System.nanoTime(), timeout.toNanos());
    }

    /** 마감까지 남은 시간. 지났으면 0이다. */
    Duration remaining() {
        return Duration.ofNanos(Math.max(0, timeoutNanos - elapsedNanos()));
    }

    Duration elapsed() {
        return Duration.ofNanos(elapsedNanos());
    }

    /**
     * 멈추기로 한 실행기가 받은 일을 모두 끝내기를 마감까지 기다린다. 기다리는 스레드가 중단되면 중단 표시를 되살리고 더 기다리지
     * 않는다.
     *
     * @return 마감 안에 모두 끝냈으면 참
     */
    boolean awaitsTermination(ExecutorService executor) {
        try {
            return executor.awaitTermination(remaining().toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return executor.isTerminated();
        }
    }

    private long elapsedNanos() {
        return System.nanoTime() - startedNanos;
    }
}
