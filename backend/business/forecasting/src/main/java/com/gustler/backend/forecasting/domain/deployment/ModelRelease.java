package com.gustler.backend.forecasting.domain.deployment;

import com.gustler.backend.forecasting.domain.model.SeatDistributionPredictor;
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
    SeatDistributionPredictor predictor
) {

    /** 이 모델이 무엇을 내는지. 만석 확률 하나가 아니라 잔여석 0석부터 70석까지의 분포다. */
    private static final String PREDICTION_TARGET_VERSION = "seat-distribution-0-70";

    /** 계수 파일이 적은 모델 판 이름과 같은 계열로 둔다. 둘이 갈리면 어느 계열인지 못 잇는다. */
    private static final String MODEL_KEY = "seat-distribution-a18";

    /**
     * 이 묶음이 그 배포가 가리키는 바로 그 계수인지 본다.
     *
     * <p>계수 파일 요약값 하나로는 모자란다. 그 요약값은 출시 식별자와 계산 규칙 판을 안 묶어서,
     * <b>같은 요약값에 다른 출시 식별자</b>가 가능하다. 그것을 같다고 보면 도는 배포는 A 인데
     * 메모리에는 B 가 올라가고, 예보를 낼 때 신원이 안 맞아 batch 가 통째로 안 돈다.
     *
     * <p>고르는 쪽과 다시 올리는 쪽이 같은 것을 재야 해서 여기 한 군데에만 둔다.
     */
    public boolean hasIdentityOf(
        ActiveModelDeployment deployment
    ) {
        return deployment.bundleDigest().equals(bundleDigest())
            && deployment.releaseId().equals(releaseId())
            && deployment.calculationVersion().equals(featureContractVersion());
    }

    public StagedModelDeployment receiptWith(
        UUID deploymentKey
    ) {
        return new StagedModelDeployment(
            deploymentKey,
            releaseId,
            MODEL_KEY,
            modelVersion,
            bundleDigest,
            PREDICTION_TARGET_VERSION,
            featureContractVersion,
            scope.digest(),
            Instant.parse(dataThrough));
    }
}
