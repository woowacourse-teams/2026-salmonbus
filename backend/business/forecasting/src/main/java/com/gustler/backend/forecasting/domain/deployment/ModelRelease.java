package com.gustler.backend.forecasting.domain.deployment;

import static com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareIdentifiers.canonical;

import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareSeatForecastModel;
import java.time.Instant;
import java.util.UUID;

/** 검증을 마친 모델과 계산에 필요한 불변 참조를 함께 보관한다. */
public record ModelRelease(
    String releaseId,
    String modelVersion,
    String bundleDigest,
    String featureContractVersion,
    String dataThrough,
    SupportedForecastScope scope,
    AlightingAwareSeatForecastModel model
) {

    public static ModelRelease alighting(
        String releaseId,
        String bundleDigest,
        String dataThrough,
        SupportedForecastScope scope,
        AlightingAwareSeatForecastModel model
    ) {
        return new ModelRelease(releaseId, "alighting-gate-base-and-pool", bundleDigest, "alighting-online-capacity-snapshot-v2",
            dataThrough, scope, model);
    }

    /**
     * 이 묶음이 배포가 가리키는 계수와 일치하는지 확인한다.
     *
     * <p>계수 파일 요약값만으로는 부족하다. 요약값에 출시 식별자와 계산 규칙 판이 포함되지 않아
     * <b>같은 요약값에 다른 출시 식별자</b>가 가능하다. 둘을 같다고 보면 활성 배포는 A인데
     * 메모리에는 B가 적재되고, 예보 발행 시 식별 정보가 일치하지 않아 batch 전체가 실행되지 않는다.
     *
     * <p>선택과 재승격에서 같은 기준으로 비교하도록 판단을 이곳에 모은다.
     */
    public boolean hasIdentityOf(
        ActiveModelDeployment deployment) {
        return deployment.bundleDigest().equals(bundleDigest())
            && deployment.releaseId().equals(releaseId())
            && canonical(deployment.calculationVersion()).equals(featureContractVersion());
    }

    public StagedModelDeployment receiptWith(
        UUID deploymentKey) {
        return new StagedModelDeployment(
            deploymentKey,
            releaseId,
            "alighting-gate-base-and-pool",
            modelVersion,
            bundleDigest,
            "after-alighting-and-departure-zero-v1",
            featureContractVersion,
            scope.digest(),
            Instant.parse(dataThrough));
    }
}
