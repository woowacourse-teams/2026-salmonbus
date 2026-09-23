package com.gustler.backend.forecasting.domain.deployment;

import java.util.Optional;

/** 활성 모델 선택을 직렬화하며 변경할 때마다 버전이 증가한다. */
public record ActiveModelSlot(long version, Optional<ActiveModelDeployment> deployment) {
    public ActiveModelSlot {
        if (version < 0 || deployment == null) {
            throw new IllegalArgumentException("활성 모델 상태가 올바르지 않습니다");
        }
    }

    public void requireVersion(long expectedVersion) {
        if (version != expectedVersion) {
            throw new ModelActivationConflictException(
                "활성 모델 버전이 변경되었습니다: expected=" + expectedVersion + ", actual=" + version);
        }
    }

    public ActiveModelSlot activate(long expectedVersion, ActiveModelDeployment target) {
        requireVersion(expectedVersion);
        if (contains(target.identity())) {
            return this;
        }
        return new ActiveModelSlot(Math.addExact(version, 1), Optional.of(target));
    }

    public boolean contains(ModelIdentity identity) {
        return deployment.map(active -> active.identity().equals(identity)).orElse(false);
    }
}
