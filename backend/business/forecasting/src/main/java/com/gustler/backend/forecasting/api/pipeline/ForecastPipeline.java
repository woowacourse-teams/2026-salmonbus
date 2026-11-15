package com.gustler.backend.forecasting.api.pipeline;

/**
 * 예보의 진입점이다. 실행하는 앱(worker)이 수집 커밋 뒤와 주기적으로 부른다.
 *
 * <p>두 메서드 모두 일을 노선별 실행 줄({@link ForecastLanes})에 넣기만 하고 예외를 던지지 않는다. 같은 배치를 여러 번 넣어도
 * 한 번만 처리된 것과 결과가 같다.
 */
public interface ForecastPipeline {
    /**
     * 수집이 배치를 커밋했다고 알린다. 수집 트랜잭션의 커밋 뒤에 부른다. 노선 열쇠를 아직 모르는 판본이면 넣지 않고 다음
     * {@link #pickUp()}에 맡긴다(수집 스레드에서 DB를 읽지 않는다).
     *
     * @param observationBatchId 커밋한 관측 배치
     * @param routeVersionId 배치의 노선 판본
     */
    void batchStored(long observationBatchId, long routeVersionId);

    /** 활성 노선 판본마다 신선도 창 안의 처리하지 않은 배치를 찾아 줄에 넣는다. 기동 직후와 주기적으로 부른다. */
    void pickUp();
}
