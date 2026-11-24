package com.gustler.backend.forecasting.domain.deployment;

import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareSeatForecastModel;
import java.time.Instant;

/**
 * 한 배치에서 사용할 활성 배포 정보와 모델을 보관한다.
 * 처리 중 다른 모델이 활성화돼도 선택한 모델을 끝까지 사용한다.
 */
public record RuntimeSnapshot(
    ActiveModelDeployment deployment,
    SupportedForecastScope scope,
    AlightingAwareSeatForecastModel model,
    Instant dataUntil
) {

    public RuntimeSnapshot {
        if (deployment == null) {
            throw new IllegalArgumentException("활성 모델 배포가 필요하다");
        }
        if (scope == null) {
            throw new IllegalArgumentException("모델 배포에는 지원하는 예보 범위가 필요하다");
        }
        if (model == null) {
            throw new IllegalArgumentException("모델 배포에는 좌석을 예측할 모델이 필요하다");
        }
    }

    /** 예보에 기록할 배포 ID다. */
    public long deploymentId() {
        return deployment.id();
    }

    public String releaseId() {
        return deployment.releaseId();
    }

    public String bundleDigest() {
        return deployment.bundleDigest();
    }

    public String featureContractVersion() {
        return deployment.calculationVersion();
    }

    public boolean covers(String sourceRouteId) {
        return scope.coversSourceRoute(sourceRouteId);
    }
}
