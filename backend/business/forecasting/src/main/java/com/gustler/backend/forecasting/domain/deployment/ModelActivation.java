package com.gustler.backend.forecasting.domain.deployment;

import java.time.Instant;
import java.util.UUID;
import java.util.Objects;

/** 한 활성화 요청의 입력과 확정된 결과다. 재시도해도 이 결과를 그대로 반환한다. */
public record ModelActivation(
    UUID requestId,
    long expectedActiveVersion,
    ActiveModelDeployment target,
    long resultingActiveVersion,
    Instant activatedAt
) {
    public ModelActivation {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(activatedAt, "activatedAt");
        if (expectedActiveVersion < 0 || resultingActiveVersion < expectedActiveVersion) {
            throw new IllegalArgumentException("활성화 요청의 버전이 올바르지 않습니다");
        }
    }

    public void requireSameRequest(long expectedVersion, ModelIdentity identity) {
        if (expectedActiveVersion != expectedVersion || !target.identity().equals(identity)) {
            throw new ModelActivationConflictException("같은 활성화 요청 ID에 다른 입력을 사용할 수 없습니다: " + requestId);
        }
    }
}
