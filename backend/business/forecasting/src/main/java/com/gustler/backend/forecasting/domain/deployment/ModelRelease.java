package com.gustler.backend.forecasting.domain.deployment;

import com.gustler.backend.forecasting.domain.model.ForecastFeatureContract;
import com.gustler.backend.forecasting.domain.model.ForecastRouteReference;
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
    SeatDistributionPredictor predictor,
    ForecastFeatureContract features,
    ForecastRouteReference routeReference
) {
    /** 모델 출력은 만석 확률 하나가 아니라 잔여석 0석부터 70석까지의 분포다. */
    private static final String PREDICTION_TARGET_VERSION = "seat-distribution-0-70";

    /** 계수 파일에 적힌 모델 판 이름과 같은 계열을 사용한다. 두 계열이 다르면 연결할 수 없다. */
    private static final String MODEL_KEY = "seat-distribution-a18";

    public ModelRelease(
        String releaseId,
        String modelVersion,
        String bundleDigest,
        String featureContractVersion,
        String dataThrough,
        SupportedForecastScope scope,
        SeatDistributionPredictor predictor
    ) {
        this(releaseId, modelVersion, bundleDigest, featureContractVersion, dataThrough, scope,
            predictor, ForecastFeatureContract.LEGACY, null);
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
