package com.gustler.backend.forecasting.api.forecast;

/**
 * 정산 작업을 큐에 등록한다. worker는 용량이 제한된 큐와 전용 단일 스레드로 구현한다.
 *
 * <p>예보 실행기와 주기 작업 스레드에서도 호출하므로 등록할 때 기다리거나 예외를 전파하면 안 된다.
 * 등록한 작업은 순서대로 실행하며 작업 하나가 예외를 던져도 다음 작업을 계속 실행한다.
 */
public interface ScoringFeed {

    /**
     * 일을 큐에 넣는다.
     *
     * @param task 정산 스레드에서 실행할 일
     * @return 넣었으면 참, 큐가 가득 찼거나 멈춰서 넣지 못했으면 거짓
     */
    boolean submit(Runnable task);
}
