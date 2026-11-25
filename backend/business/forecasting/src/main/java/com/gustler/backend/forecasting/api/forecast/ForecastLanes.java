package com.gustler.backend.forecasting.api.forecast;

/**
 * 노선별 예보 작업을 실행기에 등록한다. worker는 단일 스레드 실행기 여러 개로 구현한다.
 *
 * <p>같은 노선 키의 작업은 같은 실행기에서 등록 순서대로 실행하며 다른 실행기는 병렬로 처리할 수 있다.
 * 작업 하나가 예외를 던져도 다음 작업은 계속 실행한다.
 * 수집 스레드에서도 호출하므로 등록할 때 기다리거나 예외를 전파하면 안 된다.
 */
public interface ForecastLanes {

    /**
     * 일을 노선 키의 큐에 넣는다.
     *
     * @param routeKey 노선 키. 같은 노선 상태를 쓰는 배치는 같은 키여야 한다
     * @param task 줄의 스레드에서 실행할 일
     * @return 넣었으면 참, 큐가 가득 찼거나 멈춰서 넣지 못했으면 거짓
     */
    boolean submit(String routeKey, Runnable task);

    /**
     * 실행기가 종료되기 시작했는지다. 참이면 돌던 일은 처리하던 것 하나만 끝내고 남은 것을 시작하지 않아야 한다. 종료 중인 실행기는 돌던 일을
     * 정해진 시간만 기다리고, 그 뒤에는 DB 연결이 닫힌다.
     *
     * @return 닫히기 시작했으면 참
     */
    default boolean closing() {
        return false;
    }
}
