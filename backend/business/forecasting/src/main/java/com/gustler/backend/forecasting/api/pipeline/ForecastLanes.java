package com.gustler.backend.forecasting.api.pipeline;

/**
 * 본선({@code forecast.pipeline=v2})의 노선별 실행 줄이다. 실행하는 앱(worker)이 단일 스레드 실행기 여러 개로 구현한다.
 *
 * <p>같은 노선 열쇠의 일은 항상 같은 줄에서 넣은 순서대로 하나씩 실행하고, 다른 열쇠는 다른 줄에서 동시에 실행될 수 있다.
 * 줄 하나의 일이 예외를 던져도 다음 일을 계속 실행한다. 수집 스레드에서도 불리므로 기다리지 않고 바로 돌아와야 하며 예외를 던지지
 * 않는다.
 */
public interface ForecastLanes {
    /**
     * 일을 노선 열쇠의 줄에 넣는다.
     *
     * @param routeKey 노선 열쇠. 같은 노선 상태를 쓰는 배치는 같은 열쇠여야 한다
     * @param task 줄의 스레드에서 실행할 일
     * @return 넣었으면 참, 줄이 가득 찼거나 멈춰서 넣지 못했으면 거짓
     */
    boolean submit(String routeKey, Runnable task);
}
