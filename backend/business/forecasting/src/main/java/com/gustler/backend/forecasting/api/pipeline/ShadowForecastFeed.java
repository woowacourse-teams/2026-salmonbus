package com.gustler.backend.forecasting.api.pipeline;

/**
 * 그림자 실행을 줄에 넣는 포트다. 실행하는 앱(worker)이 길이 제한이 있는 전용 단일 스레드로 구현한다.
 *
 * <p>옛 경로의 예보 스레드에서 불리므로 기다리지 않고 바로 돌아와야 하며 예외를 던지지 않는다.
 * 줄이 가득 차면 일을 버리고 {@link ShadowForecastTask#discard()}를 부른다. 들어간 일은 넣은 순서대로 하나씩 실행한다.
 */
public interface ShadowForecastFeed {
    /**
     * 일을 줄에 넣는다.
     *
     * @param task 실행할 일
     */
    void submit(ShadowForecastTask task);
}
