package com.gustler.backend.forecasting.application.model;

import com.gustler.backend.forecasting.api.model.LoadConfiguredModel;
import com.gustler.backend.forecasting.api.model.LoadConfiguredModelCommand;
import com.gustler.backend.forecasting.api.model.ModelLoadResult;
import com.gustler.backend.forecasting.api.model.ModelLoadResult.Status;
import com.gustler.backend.forecasting.domain.deployment.ModelActivationConflictException;
import com.gustler.backend.forecasting.domain.deployment.ModelDeploymentRepository;
import java.util.UUID;

public class ModelStartupService implements LoadConfiguredModel {
    private final ModelBundleLoader loader;
    private final LoadedModelRegistry models;
    private final ModelDeploymentRepository deployments;
    private final TransactionalModelActivation activation;

    public ModelStartupService(ModelBundleLoader loader, LoadedModelRegistry models,
                               ModelDeploymentRepository deployments, TransactionalModelActivation activation) {
        this.loader = loader;
        this.models = models;
        this.deployments = deployments;
        this.activation = activation;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public ModelLoadResult load(LoadConfiguredModelCommand command) {
        if (command.directory() == null || command.directory().isBlank()) {
            return new ModelLoadResult(Status.NOT_CONFIGURED, null, 0);
        }
        var release = loader.load(command.directory());
        models.register(release);
        var slot = deployments.findActiveSlot();
        if (slot.contains(release.identity())) {
            return new ModelLoadResult(Status.READY, slot.deployment().orElseThrow().id(), slot.version());
        }
        if (slot.deployment().isPresent() && !command.promoteOnStart()) {
            return new ModelLoadResult(Status.IDENTITY_MISMATCH, slot.deployment().get().id(), slot.version());
        }
        try {
            var result = activation.activate(UUID.randomUUID(), slot.version(), release.identity());
            return new ModelLoadResult(Status.ACTIVATED, result.target().id(), result.resultingActiveVersion());
        } catch (ModelActivationConflictException conflict) {
            var latest = deployments.findActiveSlot();
            if (latest.contains(release.identity())) {
                return new ModelLoadResult(Status.READY, latest.deployment().orElseThrow().id(), latest.version());
            }
            return new ModelLoadResult(Status.ACTIVATION_CONFLICT,
                latest.deployment().map(active -> active.id()).orElse(null), latest.version());
        }
    }
}
