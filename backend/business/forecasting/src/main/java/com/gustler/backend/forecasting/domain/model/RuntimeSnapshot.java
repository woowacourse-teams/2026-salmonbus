package com.gustler.backend.forecasting.domain.model;

import com.gustler.backend.forecasting.domain.model.ActiveModelDeployment;
import com.gustler.backend.forecasting.domain.model.SeatForecastModel;
import java.time.Instant;

/**
 * 한 예보 처리에서 사용하는 활성 배포와 모델을 함께 보관한다.
 * 처리 도중 다른 모델이 활성화되어도 이미 선택한 계산 참조는 유지한다.
 */
public record RuntimeSnapshot(
    ActiveModelDeployment deployment,
    SupportedForecastScope scope,
    SeatForecastModel model,
    Instant dataUntil
) {

    public RuntimeSnapshot {
        if (deployment == null) {
            throw new IllegalArgumentException("활성 배포가 필요합니다");
        }
        if (model == null) {
            throw new IllegalArgumentException("좌석 분포를 계산할 모델이 필요합니다");
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
}
