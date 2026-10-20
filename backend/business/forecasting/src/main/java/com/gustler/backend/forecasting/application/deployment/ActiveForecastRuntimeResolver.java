package com.gustler.backend.forecasting.application.deployment;

import com.gustler.backend.forecasting.domain.deployment.ActiveModelDeployment;
import com.gustler.backend.forecasting.domain.deployment.ForecastRuntime;
import com.gustler.backend.forecasting.domain.deployment.ModelDeploymentRepository;
import com.gustler.backend.forecasting.domain.deployment.ModelRelease;
import com.gustler.backend.forecasting.domain.deployment.RuntimeSnapshot;
import com.gustler.backend.forecasting.domain.model.SeatDistributionForecastModel;
import java.time.Instant;
import java.util.Optional;

/**
 * 활성 배포 행과 메모리에 적재된 계수 묶음을 함께 반환한다.
 *
 * <p>활성 배포가 가리키는 계수 묶음 요약값으로 조회하므로 실패한 적재가 메모리에
 * 남아 있어도 선택하지 않는다.
 *
 * <p>배포와 계수의 식별 정보가 다르면 아무것도 반환하지 않는다. DB의 배포 정보와 실제 계산할 계수가
 * 어긋난 상태에서 예보를 발행하면 사용한 계수를 나중에 확인할 수 없기 때문이다.
 * 예보를 발행하지 않는 것은 이미 정상 상태이며, 계수 번들이 없는 동안 예보 배치를 실행할 때도 같다.
 */
public final class ActiveForecastRuntimeResolver implements ForecastRuntime {
    private final ModelDeploymentRepository deployments;
    private final LoadedModelRegistry bundles;

    public ActiveForecastRuntimeResolver(
        ModelDeploymentRepository deployments,
        LoadedModelRegistry bundles
    ) {
        this.deployments = deployments;
        this.bundles = bundles;
    }

    /**
     * 현재 예보에 사용할 배포와 계수 묶음이다.
     *
     * <p>조회한 값은 이후 승격이 일어나도 바뀌지 않는다. batch 하나가 이 값을 한 번 받아 끝까지
     * 사용하면 해당 batch의 모든 예보 행이 같은 계수로 계산된다.
     */
    @Override
    public Optional<RuntimeSnapshot> resolveActive() {
        Optional<ActiveModelDeployment> deployment = deployments.findActive();
        if (deployment.isEmpty()) {
            return Optional.empty();
        }
        return bundles.find(deployment.get().bundleDigest())
            .filter(bundle -> bundle.hasIdentityOf(deployment.get()))
            .map(bundle -> snapshotOf(deployment.get(), bundle));
    }

    private static RuntimeSnapshot snapshotOf(
        ActiveModelDeployment deployment,
        ModelRelease bundle
    ) {
        return new RuntimeSnapshot(
            deployment,
            bundle.scope(),
            new SeatDistributionForecastModel(bundle.predictor(), bundle.features(), bundle.routeReference()),
            dataUntilOf(bundle));
    }

    private static Instant dataUntilOf(
        ModelRelease bundle
    ) {
        return Instant.parse(bundle.dataThrough());
    }
}
