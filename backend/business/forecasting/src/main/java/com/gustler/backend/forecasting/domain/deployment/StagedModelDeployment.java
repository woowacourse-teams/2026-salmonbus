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
            throw new IllegalArgumentException("모델 적재 ID가 필요하다");
        }
        if (bundleDigest == null || bundleDigest.length() != 64) {
            throw new IllegalArgumentException("모델 번들 해시는 64자리여야 한다: " + bundleDigest);
        }
        if (dataUntil == null) {
            throw new IllegalArgumentException("학습 데이터의 기준 시각이 필요하다");
        }
    }
}
