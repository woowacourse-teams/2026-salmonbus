package com.gustler.backend.forecasting.application.deployment;

import com.gustler.backend.forecasting.api.model.ModelLoadException;
import com.gustler.backend.forecasting.domain.deployment.ModelDeploymentRepository;
import com.gustler.backend.forecasting.domain.deployment.ModelRelease;
import java.util.UUID;

/**
 * 검증된 계수 묶음의 활성화를 배포 기록과 런타임 모델에 연결한다.
 */
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

    /**
     * 검증한 번들을 메모리에 적재하고 STAGED 배포를 ACTIVE로 승격한다. 승격에 실패하면 적재한 번들을 제거하고 예외로 실패한다.
     */
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
