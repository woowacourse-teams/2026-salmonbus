package com.gustler.backend.forecasting.infrastructure.bundle;

import com.gustler.backend.forecasting.domain.model.SeatDistributionInput;
import com.gustler.backend.forecasting.domain.model.SeatDistributionPredictor;
import com.gustler.backend.forecasting.domain.model.SupportedForecastScope;

import com.gustler.backend.forecasting.domain.model.ActiveModelDeployment;
import com.gustler.backend.forecasting.domain.model.SeatForecastResult;
import java.time.Instant;
import com.gustler.backend.forecasting.domain.model.ModelIdentity;
import com.gustler.backend.forecasting.domain.model.ModelRelease;
import com.gustler.backend.forecasting.domain.model.SeatDistributionForecastModel;

/** 파일 구조와 대조 계산을 검증한 모델 계수다. */
public record LoadedBundle(
    CoefficientBundle coefficients,
    SeatDistributionPredictor predictor
) {

    /** 크기 묶음 아홉 개의 상대 경계. 학습 쪽 상수와 같아야 한다. */
    private static final double[] RELATIVE_BIN_EDGES =
        {0.0, 0.03, 0.07, 0.12, 0.2, 0.32, 0.48, 0.7, 1.0};

    /** 대조 사례를 재현했다고 볼 폭. 부동소수점 반올림만 넘긴다. */
    private static final double GOLDEN_TOLERANCE = 1e-9;

    /**
     * 파일에서 읽어 검사하고 예측기까지 세운다.
     *
     * <p>마지막에 <b>계수 파일이 실은 대조 사례를 우리 예측기로 실제로 돌려 본다.</b> 크기와
     * 자료형만 맞는 계수 파일이 다른 계산으로 예보를 내는 것을 여기서만 잡을 수 있다. 통과 못 하면
     * 배포 행이 서기 전에 멈춘다.
     */
    public static LoadedBundle from(
        BundleFiles files
    ) {
        CoefficientBundle coefficients = BundleLoader.load(files);
        LoadedBundle bundle = new LoadedBundle(
            coefficients, new SeatDistributionPredictor(coefficients, RELATIVE_BIN_EDGES));
        bundle.verifyGoldenVector();
        return bundle;
    }

    /** 대조 사례를 재현하는지 본다. 만석 확률과 기대 잔여석을 소수점 아래 아홉째 자리까지 본다. */
    private void verifyGoldenVector() {
        BundleManifest.GoldenVector golden = coefficients.manifest().goldenVector();
        SeatForecastResult actual = predictor.predict(new SeatDistributionInput(
            golden.featureVector().stream().mapToDouble(Double::doubleValue).toArray(),
            golden.modelRoute(),
            golden.stopsAhead(),
            golden.currentSeats(),
            golden.capacity(),
            null));

        requireSame("만석 확률", golden.expectedFullChance(), actual.distribution().fullChance());
        requireSame("기대 잔여석", golden.expectedSeats(), actual.distribution().expectedSeats());
    }

    private static void requireSame(
        String name,
        final double expected,
        final double actual
    ) {
        BundleCheck.GOLDEN_VECTOR.require(
            Math.abs(expected - actual) <= GOLDEN_TOLERANCE,
            "%s 가 다르다. 계수 파일 %s, 우리 계산 %s".formatted(name, expected, actual));
    }

    /** DB 배포와 준비된 모델의 모든 식별 정보를 비교한다. */
    public boolean hasIdentityOf(ActiveModelDeployment deployment) {
        return identity().equals(deployment.identity());
    }

    public ModelIdentity identity() {
        BundleManifest manifest = coefficients.manifest();
        return new ModelIdentity(manifest.releaseId(), "seat-distribution-a18", manifest.modelVersion(),
            manifest.identityDigest(), "seat-distribution-0-70", manifest.featureContractVersion(),
            scope().digest(), Instant.parse(manifest.dataThrough()));
    }

    public ModelRelease release() {
        return new ModelRelease(identity(), scope(), new SeatDistributionForecastModel(predictor));
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

}
