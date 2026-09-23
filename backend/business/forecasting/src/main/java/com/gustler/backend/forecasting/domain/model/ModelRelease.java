package com.gustler.backend.forecasting.domain.model;

import java.util.Objects;

/** 검증을 마친 모델과 계산에 필요한 불변 참조를 함께 보관한다. */
public record ModelRelease(ModelIdentity identity, SupportedForecastScope scope, SeatForecastModel model) {
    public ModelRelease {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(model, "model");
        if (!identity.supportedScopeDigest().equals(scope.digest())) {
            throw new IllegalArgumentException("모델 식별 정보와 지원 노선이 다릅니다");
        }
    }

    public RuntimeSnapshot runtimeFor(ActiveModelDeployment deployment) {
        if (!identity.equals(deployment.identity())) {
            throw new IllegalArgumentException("활성 배포와 준비된 모델의 식별 정보가 다릅니다");
        }
        return new RuntimeSnapshot(deployment, scope, model, identity.dataUntil());
    }
}
