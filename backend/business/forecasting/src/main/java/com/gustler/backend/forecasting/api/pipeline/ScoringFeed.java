package com.gustler.backend.forecasting.api.pipeline;

/**
 * 채점 일을 줄에 넣는 포트다. 실행하는 앱(worker)이 길이 제한이 있는 전용 단일 스레드로 구현한다.
 *
 * <p>예보 실행 줄의 스레드와 주기 작업 스레드에서 불리므로 기다리지 않고 바로 돌아와야 하며 예외를 던지지 않는다. 들어간 일은 넣은
 * 순서대로 한 스레드에서 하나씩 실행한다. 일 하나가 예외를 던져도 다음 일을 계속 실행한다.
 */
public interface ScoringFeed {
    /**
     * 일을 줄에 넣는다.
     *
     * @param task 채점 스레드에서 실행할 일
     * @return 넣었으면 참, 줄이 가득 찼거나 멈춰서 넣지 못했으면 거짓
     */
    boolean submit(Runnable task);
}
