package com.gustler.backend.forecasting.application.deployment;

import com.gustler.backend.forecasting.api.model.LoadConfiguredModel;
import com.gustler.backend.forecasting.api.model.LoadConfiguredModelCommand;
import com.gustler.backend.forecasting.api.model.ModelLoadException;
import com.gustler.backend.forecasting.api.model.ModelLoadResult;
import com.gustler.backend.forecasting.domain.deployment.ActiveModelDeployment;
import com.gustler.backend.forecasting.domain.deployment.ModelDeploymentRepository;
import com.gustler.backend.forecasting.domain.deployment.ModelRelease;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

public class ModelStartupService implements LoadConfiguredModel {

    private final ModelBundleLoader loader;
    private final ModelDeploymentRepository deployments;
    private final LoadedModelRegistry bundles;
    private final BundleActivation activation;

    public ModelStartupService(
        ModelBundleLoader loader,
        ModelDeploymentRepository deployments,
        LoadedModelRegistry bundles,
        BundleActivation activation
    ) {
        this.loader = loader;
        this.deployments = deployments;
        this.bundles = bundles;
        this.activation = activation;
    }

    @Override
    @Transactional
    public ModelLoadResult load(
        LoadConfiguredModelCommand command
    ) {
        if (command.directory() == null || command.directory().isBlank()) {
            return new ModelLoadResult.NotConfigured();
        }
        try {
            return load(loader.filesUnder(command.directory()), command.promoteOnStart());
        } catch (ModelLoadException error) {
            return new ModelLoadResult.Rejected(error.getMessage());
        }
    }

    private ModelLoadResult load(
        ModelBundleFiles files,
        final boolean promoteOnStart
    ) {
        Optional<ActiveModelDeployment> active = deployments.findActive();
        if (active.isEmpty()) {
            return new ModelLoadResult.Activated(activation.activate(files));
        }

        ModelRelease bundle = files.load();
        if (bundle.hasIdentityOf(active.get())) {
            bundles.add(bundle);
            return new ModelLoadResult.Reloaded(bundle.releaseId());
        }
        if (!promoteOnStart) {
            return new ModelLoadResult.IdentityMismatch(active.get().releaseId(), bundle.releaseId());
        }
        return new ModelLoadResult.Promoted(
            active.get().releaseId(), bundle.releaseId(), activation.activate(files));
    }
}
