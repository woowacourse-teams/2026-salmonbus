package com.gustler.backend.forecasting.domain.deployment;

import java.time.Instant;
import java.util.UUID;

/** 검증을 마치고 활성화를 기다리는 배포의 저장 정보다. */
public record StagedModelDeployment(
    UUID deploymentKey,
    String releaseId,
    String modelKey,
    String modelVersion,
    String bundleDigest,
    String predictionTargetVersion,
    String calculationVersion,
    String supportedScopeDigest,
    Instant dataUntil
) {

    public StagedModelDeployment {
        if (deploymentKey == null) {
            throw new IllegalArgumentException("적재마다 다른 키가 있어야 한다");
        }
        if (bundleDigest == null || bundleDigest.length() != 64) {
            throw new IllegalArgumentException("계수 묶음 요약값은 64자리다: " + bundleDigest);
        }
        if (dataUntil == null) {
            throw new IllegalArgumentException("학습 자료가 어디까지인지 있어야 한다");
        }
    }
}
