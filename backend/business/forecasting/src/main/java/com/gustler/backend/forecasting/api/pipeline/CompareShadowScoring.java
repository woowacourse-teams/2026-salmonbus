package com.gustler.backend.forecasting.api.pipeline;

/**
 * 새 채점 결과를 옛 정산 결과와 예측마다 대조하는 주기 작업이다. 실행하는 앱(worker)이 몇 분마다 한 스레드에서 부른다.
 *
 * <p>읽기만 하고 결과는 지표({@code salmonbus.pipeline.scoring.compared{result}})와 경고 로그로 남긴다.
 */
public interface CompareShadowScoring {
    /** 지난 회차 뒤에 옛 정산이 결과를 다 적었을 범위를 대조한다. */
    void compareRecent();
}
