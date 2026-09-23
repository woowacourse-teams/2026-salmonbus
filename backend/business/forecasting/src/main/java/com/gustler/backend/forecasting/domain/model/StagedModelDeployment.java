package com.gustler.backend.forecasting.domain.model;

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
            throw new IllegalArgumentException("배포 요청마다 식별 키가 필요합니다");
        }
        if (bundleDigest == null || bundleDigest.length() != 64) {
            throw new IllegalArgumentException("계수 파일의 SHA-256 값은 64자리여야 합니다: " + bundleDigest);
        }
        if (dataUntil == null) {
            throw new IllegalArgumentException("학습 자료의 기준 시각이 필요합니다");
        }
    }
}
