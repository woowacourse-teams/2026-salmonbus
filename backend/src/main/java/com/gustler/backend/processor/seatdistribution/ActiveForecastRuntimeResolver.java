package com.gustler.backend.processor.seatdistribution;

import com.gustler.backend.processor.ActiveModelDeployment;
import com.gustler.backend.processor.ModelDeploymentRepository;
import java.time.Instant;
import java.util.Optional;

/**
 * 도는 배포 행과 올라온 계수 묶음을 한 덩어리로 낸다.
 *
 * <p>도는 배포가 가리키는 계수 묶음 요약값으로 찾는다. 그래서 올리다 실패한 적재가 메모리에
 * 남아 있어도 안 골라진다.
 *
 * <p>둘의 신원이 다르면 아무것도 안 낸다. 다르다는 것은 DB 에 적힌 배포와 실제로 계산할 계수가
 * 어긋났다는 뜻이고, 그 상태로 낸 예보는 어느 계수로 낸 것인지 나중에 알 수 없다.
 * 예보를 안 내는 것은 이미 정상 상태다. 계수 번들이 없는 동안 예보 배치가 도는 방식과 같다.
 */
public final class ActiveForecastRuntimeResolver {

    private final ModelDeploymentRepository deployments;
    private final LoadedBundleHolder bundles;

    public ActiveForecastRuntimeResolver(
        ModelDeploymentRepository deployments,
        LoadedBundleHolder bundles
    ) {
        this.deployments = deployments;
        this.bundles = bundles;
    }

    /**
     * 지금 예보에 쓸 것 하나.
     *
     * <p>받아 든 것은 뒤에 승격이 일어나도 안 바뀐다. batch 하나가 이것을 한 번 받아 끝까지
     * 쓰면 그 batch 의 모든 예보 행이 같은 계수에서 나온다.
     */
    public Optional<RuntimeSnapshot> resolveActive() {
        Optional<ActiveModelDeployment> deployment = deployments.findActive();
        if (deployment.isEmpty()) {
            return Optional.empty();
        }
        return bundles.find(deployment.get().bundleDigest())
            .filter(bundle -> sameIdentity(deployment.get(), bundle))
            .map(bundle -> snapshotOf(deployment.get(), bundle));
    }

    private static boolean sameIdentity(
        ActiveModelDeployment deployment,
        LoadedBundle bundle
    ) {
        return deployment.releaseId().equals(bundle.releaseId())
            && deployment.bundleDigest().equals(bundle.bundleDigest())
            && deployment.calculationVersion().equals(bundle.featureContractVersion());
    }

    private static RuntimeSnapshot snapshotOf(
        ActiveModelDeployment deployment,
        LoadedBundle bundle
    ) {
        return new RuntimeSnapshot(
            deployment.id(),
            bundle.releaseId(),
            bundle.bundleDigest(),
            bundle.featureContractVersion(),
            bundle.scope(),
            bundle.predictor(),
            dataUntilOf(bundle));
    }

    private static Instant dataUntilOf(
        LoadedBundle bundle
    ) {
        return Instant.parse(bundle.coefficients().manifest().dataThrough());
    }
}
