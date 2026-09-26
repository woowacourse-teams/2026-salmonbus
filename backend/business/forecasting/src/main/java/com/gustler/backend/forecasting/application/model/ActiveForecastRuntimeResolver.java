package com.gustler.backend.forecasting.application.model;

import com.gustler.backend.forecasting.domain.deployment.ForecastRuntime;
import com.gustler.backend.forecasting.domain.deployment.ModelDeploymentRepository;
import com.gustler.backend.forecasting.domain.deployment.RuntimeSnapshot;
import java.util.Optional;

public final class ActiveForecastRuntimeResolver implements ForecastRuntime {
    private final ModelDeploymentRepository deployments;
    private final LoadedModelRegistry models;

    public ActiveForecastRuntimeResolver(ModelDeploymentRepository deployments, LoadedModelRegistry models) {
        this.deployments = deployments;
        this.models = models;
    }

    @Override
    public Optional<RuntimeSnapshot> resolveActive() {
        return deployments.findActive()
            .flatMap(active -> models.find(active.identity()).map(bundle -> bundle.runtimeFor(active)));
    }
}
