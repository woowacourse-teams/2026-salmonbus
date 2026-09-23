package com.gustler.backend.forecasting.domain.model;

import java.util.Objects;

/** 현재 활성 배포와 그 배포가 사용하는 모델의 식별 정보다. */
public record ActiveModelDeployment(long id, ModelIdentity identity) {
    public ActiveModelDeployment {
        if (id <= 0) {
            throw new IllegalArgumentException("배포 ID는 양수여야 합니다");
        }
        Objects.requireNonNull(identity, "identity");
    }

    public String calculationVersion() { return identity.calculationVersion(); }
    public String releaseId() { return identity.releaseId(); }
    public String bundleDigest() { return identity.bundleDigest(); }
}
