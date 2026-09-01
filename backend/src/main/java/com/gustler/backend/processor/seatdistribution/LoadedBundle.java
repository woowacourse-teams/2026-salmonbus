package com.gustler.backend.processor.seatdistribution;

import java.util.UUID;

/**
 * 검사를 통과해 메모리에 올라온 계수 묶음 하나.
 *
 * <p>DB 에 아직 안 올라갔을 수도 있고 이미 도는 중일 수도 있다. 어느 쪽이든 <b>계수와 신원이
 * 여기서 갈리지 않는다.</b> 예측기를 이 묶음에서만 만들기 때문에, 예측기를 손에 넣었다는 것이
 * 곧 그 신원의 계수를 쓰고 있다는 뜻이 된다.
 */
public record LoadedBundle(
    CoefficientBundle coefficients,
    SeatDistributionPredictor predictor
) {

    /** 크기 묶음 아홉 개의 상대 경계. 학습 쪽 상수와 같아야 한다. */
    private static final double[] RELATIVE_BIN_EDGES =
        {0.0, 0.03, 0.07, 0.12, 0.2, 0.32, 0.48, 0.7, 1.0};

    public static LoadedBundle from(
        BundleFiles files
    ) {
        CoefficientBundle coefficients = BundleLoader.load(files);
        return new LoadedBundle(coefficients, new SeatDistributionPredictor(coefficients, RELATIVE_BIN_EDGES));
    }

    public String releaseId() {
        return coefficients.manifest().releaseId();
    }

    public String bundleDigest() {
        return coefficients.manifest().identityDigest();
    }

    public String featureContractVersion() {
        return coefficients.manifest().featureContractVersion();
    }

    public SupportedForecastScope scope() {
        return new SupportedForecastScope(coefficients.routes());
    }

    public ModelDeploymentReceipt receiptWith(
        UUID deploymentKey
    ) {
        return ModelDeploymentReceipt.of(coefficients.manifest(), scope(), deploymentKey);
    }
}
