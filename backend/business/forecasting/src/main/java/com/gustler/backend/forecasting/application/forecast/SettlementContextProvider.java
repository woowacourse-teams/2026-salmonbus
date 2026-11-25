package com.gustler.backend.forecasting.application.forecast;

import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * 모델 배포가 노선 버전마다 한 번 기록해 둔 정산 근거({@code model_deployment.settlement_context -> 노선 버전})를 반환한다.
 *
 * <p>근거의 구조는 {@code {"shared":{capacityPolicy,inputProfile,turn,realStopOrders,[extendedSettlement],[holidays]},
 * "stops":{"목표 정류장":[{stop,band,lam,p,kappa,r}]}}}이다. 계수 해시({@code parameter_hash})는 계수 노드의 글자 표현에서
 * 나오므로, 구현은 DB가 반환한 jsonb 텍스트를 그대로 읽어 만든 노드를 반환해야 한다. 다른 경로로 다시 직렬화하면 키 순서가 달라져
 * 해시가 바뀔 수 있다.
 * 반환한 노드는 읽기만 하며 바꾸지 않는다.
 */
public interface SettlementContextProvider {

    /**
     * 정산 근거를 찾는다.
     *
     * @param modelDeploymentId 예측을 낸 모델 배포
     * @param routeVersionId 예측이 속한 노선 버전
     * @return 근거가 아직 없으면 빈 값
     */
    Optional<JsonNode> find(long modelDeploymentId, long routeVersionId);
}
