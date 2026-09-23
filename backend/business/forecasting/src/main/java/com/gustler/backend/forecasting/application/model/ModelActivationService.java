package com.gustler.backend.forecasting.application.model;

import com.gustler.backend.forecasting.api.model.ActivateModel;
import com.gustler.backend.forecasting.api.model.ActivateModelCommand;
import com.gustler.backend.forecasting.api.model.ModelActivationResult;
import com.gustler.backend.forecasting.domain.model.ModelActivation;
import com.gustler.backend.forecasting.domain.model.ModelRelease;

public class ModelActivationService implements ActivateModel {
    private final ModelBundleLoader loader;
    private final LoadedModelRegistry models;
    private final TransactionalModelActivation activation;

    public ModelActivationService(ModelBundleLoader loader, LoadedModelRegistry models,
                                  TransactionalModelActivation activation) {
        this.loader = loader;
        this.models = models;
        this.activation = activation;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public ModelActivationResult activate(ActivateModelCommand command) {
        ModelRelease release = loader.load(command.directory());
        models.register(release);
        ModelActivation result = activation.activate(command.requestId(), command.expectedActiveVersion(), release.identity());
        return new ModelActivationResult(result.target().id(), result.resultingActiveVersion(), result.activatedAt());
    }
}
