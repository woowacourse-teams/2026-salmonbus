package com.gustler.backend.forecasting.api.forecast;

/**
 * 예보의 진입점이다. 실행하는 앱(worker)이 수집 커밋 뒤와 주기적으로 호출한다.
 *
 * <p>두 메서드 모두 노선 버전의 처리하지 않은 배치를 수신 순서로 처리하는 일을 노선별 실행기({@link ForecastLanes})에 넣기만 하고
 * 예외를 던지지 않는다. 여러 번 불러도 배치는 한 번만 처리된다.
 */
public interface RunForecasts {

    /**
     * 수집이 배치를 커밋했다고 알린다. 수집 트랜잭션의 커밋 뒤에 호출한다. 예보는 알린 배치 하나가 아니라 그 노선 버전의 처리하지
     * 않은 배치를 수신 순서로 모두 처리한다. 노선 키를 아직 모르는 버전이면 넣지 않고 다음 {@link #pickUp()}에 맡긴다(수집
     * 스레드에서 DB를 읽지 않는다).
     *
     * @param observationBatchId 커밋한 관측 배치
     * @param routeVersionId 배치의 노선 버전
     */
    void batchStored(long observationBatchId, long routeVersionId);

    /** 유효 시간 범위 안에 처리하지 않은 배치가 있는 활성 노선 버전을 찾아 그 버전의 처리를 큐에 넣는다. 기동 직후와 주기적으로 호출한다. */
    void pickUp();
}
