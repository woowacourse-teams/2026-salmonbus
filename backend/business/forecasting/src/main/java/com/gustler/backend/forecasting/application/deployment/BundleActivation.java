package com.gustler.backend.forecasting.application.deployment;

import com.gustler.backend.forecasting.api.model.ModelLoadException;
import com.gustler.backend.forecasting.domain.deployment.ModelDeploymentRepository;
import com.gustler.backend.forecasting.domain.deployment.ModelRelease;
import java.util.UUID;

public final class BundleActivation {

    private final ModelDeploymentRepository deployments;
    private final LoadedModelRegistry bundles;

    public BundleActivation(
        ModelDeploymentRepository deployments,
        LoadedModelRegistry bundles
    ) {
        this.deployments = deployments;
        this.bundles = bundles;
    }

    public long activate(
        ModelBundleFiles files
    ) {
        ModelRelease bundle = files.load();
        final long deploymentId = deployments.stage(bundle.receiptWith(UUID.randomUUID()));
        bundles.add(bundle);
        if (!deployments.promoteToActive(deploymentId)) {
            bundles.remove(bundle.bundleDigest());
            throw new ModelLoadException(
                "STAGED 에서 ACTIVE 로 못 올렸다. 그 사이 다른 적재가 먼저 올라갔다: " + deploymentId);
        }
        return deploymentId;
    }
}
