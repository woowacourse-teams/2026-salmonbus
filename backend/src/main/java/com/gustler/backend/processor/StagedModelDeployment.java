package com.gustler.backend.processor;

import java.time.Instant;
import java.util.UUID;

/**
 * 아직 안 도는 계수 배포 한 줄. 적재는 끝났고 승격을 기다린다.
 *
 * <p>열 이름과 같은 말로 담는다. 이 값을 만드는 것은 계수 묶음을 읽은 쪽이고,
 * 여기서는 어느 열에 무엇이 들어가는지만 안다.
 */
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
