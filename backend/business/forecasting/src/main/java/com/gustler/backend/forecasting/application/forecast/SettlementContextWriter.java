package com.gustler.backend.forecasting.application.forecast;

/**
 * 모델 배포에 노선 판본의 정산 근거를 한 번 적는 포트다. 채점이 이 근거를 읽는다.
 *
 * <p>이미 같은 값이 있으면 아무것도 하지 않고, 다른 값이 있으면 거절한다. 행 잠금을 잡지 않는다.
 */
public interface SettlementContextWriter {

    /**
     * 정산 근거를 적는다.
     *
     * @param modelDeploymentId 모델 배포
     * @param routeVersionId 노선 판본
     * @param context 정산 근거 JSON({@code SettlementContextDocument.of})
     * @throws IllegalStateException 같은 (모델 배포, 노선 판본)에 다른 근거가 이미 있으면 던진다
     */
    void store(long modelDeploymentId, long routeVersionId, String context);
}
