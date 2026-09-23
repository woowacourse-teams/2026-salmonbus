package com.gustler.backend.forecasting.domain.deployment;

import java.util.Optional;
import java.util.UUID;

public interface ModelDeploymentRepository {
    Optional<ActiveModelDeployment> findActive();
    ActiveModelSlot findActiveSlot();
    ActiveModelSlot lockActiveSlot();
    Optional<ModelActivation> findActivation(UUID requestId);
    long stage(StagedModelDeployment staged);
    void activate(long deploymentId, ActiveModelSlot previous, long resultingVersion);
    void recordActivation(ModelActivation activation);
}
