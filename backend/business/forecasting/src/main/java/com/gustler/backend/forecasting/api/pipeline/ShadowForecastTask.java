package com.gustler.backend.forecasting.api.pipeline;

/**
 * 그림자 실행 한 건이다. 옛 경로가 끝낸 배치 하나를 새 경로로 다시 계산하고 대조해 새 테이블에 쓰는 일을 담는다.
 *
 * <p>{@link #run()}은 그림자 전용 스레드에서만 부른다. 실패는 안에서 로그·지표로 남기고 그 노선의 상태를 버리므로 예외를 던지지
 * 않는다. 줄이 가득 차 이 일을 버릴 때는 {@link #run()} 대신 {@link #discard()}를 부른다. 그러면 그 노선의 메모리 상태를 버려
 * 다음 배치에서 되감아 다시 만든다.
 */
public interface ShadowForecastTask {
    /** 처리할 관측 배치. 로그용이다. */
    long observationBatchId();

    /** 상태 단위인 노선 이름. 로그용이다. */
    String route();

    /** 새 경로로 계산하고 대조해 저장한다. */
    void run();

    /** 처리하지 않고 버린다. 그 노선의 상태를 버리고 버린 수를 센다. 어느 스레드에서 불러도 된다. */
    void discard();
}
