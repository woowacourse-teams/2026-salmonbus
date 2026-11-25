package com.gustler.backend.forecasting.application.deployment;

import com.gustler.backend.forecasting.domain.deployment.ActiveModelDeployment;
import com.gustler.backend.forecasting.domain.deployment.ForecastRuntime;
import com.gustler.backend.forecasting.domain.deployment.ModelDeploymentRepository;
import com.gustler.backend.forecasting.domain.deployment.ModelRelease;
import com.gustler.backend.forecasting.domain.deployment.RuntimeSnapshot;
import java.time.Instant;
import java.util.Optional;

/**
 * 활성 배포 행과 메모리에 적재된 모델 번들을 같이 반환한다.
 *
 * <p>활성 배포가 가리키는 모델 번들 해시로 조회하므로 실패한 적재가 메모리에
 * 남아 있어도 선택하지 않는다.
 *
 * <p>배포와 계수의 식별 정보가 다르면 아무것도 반환하지 않는다. DB의 배포 정보와 실제 계산할 계수가
 * 어긋난 상태에서 예보를 발행하면 사용한 계수를 나중에 확인할 수 없기 때문이다.
 * 예보를 발행하지 않는 것은 이미 정상 상태이며, 모델 번들이 없는 동안 예보 배치를 실행할 때도 같다.
 */
public final class ActiveForecastRuntimeResolver implements ForecastRuntime {

    private final ModelDeploymentRepository deployments;
    private final LoadedModelRegistry bundles;

    public ActiveForecastRuntimeResolver(ModelDeploymentRepository deployments, LoadedModelRegistry bundles) {
        this.deployments = deployments;
        this.bundles = bundles;
    }

    /**
     * 현재 배치에서 사용할 배포 정보와 모델 번들이다.
     *
     * <p>조회 이후 다른 모델이 활성화돼도 이 값은 바뀌지 않는다.
     * 배치마다 한 번 조회해 끝까지 사용하면 모든 예보에 같은 모델을 적용할 수 있다.
     */
    @Override
    public Optional<RuntimeSnapshot> resolveActive() {
        Optional<ActiveModelDeployment> foundDeployment = deployments.findActive();
        if (foundDeployment.isEmpty()) {
            return Optional.empty();
        }
        ActiveModelDeployment activeDeployment = foundDeployment.get();
        return bundles.find(activeDeployment.bundleDigest())
            .filter(bundle -> bundle.hasIdentityOf(activeDeployment))
            .map(bundle -> snapshotOf(activeDeployment, bundle));
    }

    private static RuntimeSnapshot snapshotOf(ActiveModelDeployment deployment, ModelRelease bundle) {
        return new RuntimeSnapshot(
                deployment,
                bundle.scope(),
                bundle.model(),
                Instant.parse(bundle.dataThrough())
        );
    }
}
