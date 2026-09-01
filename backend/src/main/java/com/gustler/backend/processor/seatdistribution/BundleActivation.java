package com.gustler.backend.processor.seatdistribution;

import com.gustler.backend.processor.ModelDeploymentRepository;
import com.gustler.backend.processor.StagedModelDeployment;
import java.util.UUID;

/**
 * 계수 묶음을 적재해 도는 배포로 올린다.
 *
 * <p>세 걸음이다. 읽어서 검사하고, STAGED 로 영수증을 남기고, 계수를 메모리에 올린 뒤 ACTIVE 로
 * 올린다. <b>검사에 걸리면 첫 걸음에서 멈춘다.</b> DB 에 행이 안 남고 메모리도 안 바뀐다.
 *
 * <p><b>부르는 쪽이 transaction 하나로 감싼다.</b> 여기에 애너테이션을 안 다는 이유는 이 클래스가
 * 아직 빈이 아니어서다. 빈이 아니면 프록시를 안 거쳐 애너테이션이 아무 일도 안 하는데, 달려 있으면
 * 걸린 줄 알게 된다. 예측기를 예보 경로에 붙일 때 빈으로 올리면서 같이 단다.
 *
 * <p>계수를 먼저 올리고 승격을 나중에 하는 순서인 이유는, 반대로 하면 ACTIVE 행은 새 배포인데
 * 메모리에는 아직 옛 계수인 순간이 생기기 때문이다. 그 순간에 도는 batch 가 어긋난 짝으로
 * 예보를 낸다. 이 순서면 그 사이에는 배포 행과 계수의 신원이 안 맞아 예보를 아예 안 낸다.
 */
public final class BundleActivation {

    private final ModelDeploymentRepository deployments;
    private final LoadedBundleHolder bundles;

    public BundleActivation(
        ModelDeploymentRepository deployments,
        LoadedBundleHolder bundles
    ) {
        this.deployments = deployments;
        this.bundles = bundles;
    }

    public long activate(
        BundleFiles files
    ) {
        LoadedBundle bundle = LoadedBundle.from(files);
        final long deploymentId = deployments.stage(stagedOf(bundle));
        bundles.replaceWith(bundle);
        if (!deployments.promoteToActive(deploymentId)) {
            throw new BundleRejectedException(
                "STAGED 에서 ACTIVE 로 못 올렸다. 그 사이 다른 적재가 먼저 올라갔다: " + deploymentId);
        }
        return deploymentId;
    }

    private static StagedModelDeployment stagedOf(
        LoadedBundle bundle
    ) {
        ModelDeploymentReceipt receipt = bundle.receiptWith(UUID.randomUUID());
        return new StagedModelDeployment(
            receipt.deploymentKey(),
            receipt.releaseId(),
            receipt.modelKey(),
            receipt.modelVersion(),
            receipt.bundleDigest(),
            receipt.predictionTargetVersion(),
            receipt.calculationVersion(),
            receipt.supportedScopeDigest(),
            receipt.dataUntil());
    }
}
