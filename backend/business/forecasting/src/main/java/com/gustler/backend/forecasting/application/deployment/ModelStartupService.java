package com.gustler.backend.forecasting.application.deployment;

import com.gustler.backend.forecasting.api.model.LoadConfiguredModel;
import com.gustler.backend.forecasting.api.model.LoadConfiguredModelCommand;
import com.gustler.backend.forecasting.api.model.ModelLoadException;
import com.gustler.backend.forecasting.api.model.ModelLoadResult;
import com.gustler.backend.forecasting.api.model.ModelLoadResult.Activated;
import com.gustler.backend.forecasting.api.model.ModelLoadResult.IdentityMismatch;
import com.gustler.backend.forecasting.api.model.ModelLoadResult.NotConfigured;
import com.gustler.backend.forecasting.api.model.ModelLoadResult.Promoted;
import com.gustler.backend.forecasting.api.model.ModelLoadResult.Rejected;
import com.gustler.backend.forecasting.api.model.ModelLoadResult.Reloaded;
import com.gustler.backend.forecasting.domain.deployment.ActiveModelDeployment;
import com.gustler.backend.forecasting.domain.deployment.ModelDeploymentRepository;
import com.gustler.backend.forecasting.domain.deployment.ModelRelease;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기동 시 사용할 모델을 적재해 런타임 조회 경계에 제공한다.
 * 모델 파일 검증 실패를 사용 가능한 모델로 숨기지 않는다.
 */
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
    @Transactional(readOnly = false)
    public ModelLoadResult load(
        LoadConfiguredModelCommand command
    ) {
        if (command.directory() == null || command.directory().isBlank()) {
            return new NotConfigured();
        }
        try {
            return load(loader.filesUnder(command.directory()), command.promoteOnStart());
        } catch (ModelLoadException error) {
            return new Rejected(error.getMessage());
        }
    }

    private ModelLoadResult load(
        ModelBundleFiles files,
        final boolean promoteOnStart
    ) {
        Optional<ActiveModelDeployment> active = deployments.findActive();
        if (active.isEmpty()) {
            return new Activated(activation.activate(files));
        }

        ModelRelease bundle = files.load();
        if (bundle.hasIdentityOf(active.get())) {
            bundles.add(bundle);
            return new Reloaded(bundle.releaseId());
        }
        if (!promoteOnStart) {
            return new IdentityMismatch(active.get().releaseId(), bundle.releaseId());
        }
        return new Promoted(
            active.get().releaseId(), bundle.releaseId(), activation.activate(files));
    }
}
