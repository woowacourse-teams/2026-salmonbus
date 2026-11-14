package com.gustler.backend.forecasting.application.publication;

/**
 * 옛 발행 경로가 확정 판본 배치 하나를 끝냈다는 알림을 받는다. 그림자 실행이 이 알림으로 같은 배치를 새 경로로 다시 계산한다.
 *
 * <p>옛 경로의 예보 스레드에서 불리므로 구현은 알림을 줄에 넣는 일만 하고 바로 돌아와야 한다. 예외를 밖으로 던지지 않아야 하지만,
 * 옛 경로는 혹시 던져진 예외도 삼켜 자기 결과를 바꾸지 않는다.
 */
public interface LegacyForecastListener {
    /**
     * 배치 하나의 옛 경로 결과를 넘긴다.
     *
     * @param result 옛 경로가 커밋한 발행 또는 예보하지 않은 사유
     */
    void batchFinished(LegacyForecastResult result);
}
