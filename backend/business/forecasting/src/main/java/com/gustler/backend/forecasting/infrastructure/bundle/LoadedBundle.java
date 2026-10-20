package com.gustler.backend.forecasting.infrastructure.bundle;

import com.gustler.backend.forecasting.domain.deployment.ModelRelease;
import com.gustler.backend.forecasting.domain.deployment.SupportedForecastScope;
import com.gustler.backend.forecasting.domain.model.ForecastFeatureContract;
import com.gustler.backend.forecasting.domain.model.ForecastRouteReference;
import com.gustler.backend.forecasting.domain.model.SeatDistributionInput;
import com.gustler.backend.forecasting.domain.model.SeatDistributionPredictor;
import com.gustler.backend.forecasting.domain.model.SeatForecastResult;
import com.gustler.backend.forecasting.infrastructure.bundle.BundleManifest.GoldenVector;

/** 파일 구조와 대조 계산을 검증한 모델 계수다. */
public record LoadedBundle(
    CoefficientBundle coefficients,
    SeatDistributionPredictor predictor,
    ForecastRouteReference routeReference
) {
    /** 크기 묶음 아홉 개의 상대 경계. 학습 쪽 상수와 같아야 한다. */
    private static final double[] RELATIVE_BIN_EDGES =
        {0.0, 0.03, 0.07, 0.12, 0.2, 0.32, 0.48, 0.7, 1.0};

    /** 대조 사례 재현 여부를 판단하는 허용 오차. 부동소수점 반올림 오차만 허용한다. */
    private static final double GOLDEN_TOLERANCE = 1e-9;

    /**
     * 파일을 읽고 검증한 뒤 예측기를 생성한다.
     *
     * <p>마지막에 계수 파일에 포함된 대조 사례를 예측기로 실행한다. 크기와 자료형은 맞지만
     * 계산이 다른 계수 파일은 이 단계에서만 구분할 수 있다. 통과하지 못하면
     * 배포 행을 생성하기 전에 중단한다.
     */
    public static LoadedBundle from(
        BundleFiles files
    ) {
        CoefficientBundle coefficients = BundleLoader.load(files);
        ForecastRouteReference reference = readRouteReference(files, coefficients);
        LoadedBundle bundle = new LoadedBundle(
            coefficients, new SeatDistributionPredictor(coefficients, RELATIVE_BIN_EDGES), reference);
        bundle.verifyGoldenVector();
        return bundle;
    }

    /** 대조 사례를 재현하는지 본다. 만석 확률과 기대 잔여석을 소수점 아래 아홉째 자리까지 본다. */
    private void verifyGoldenVector() {
        GoldenVector golden = coefficients.manifest().goldenVector();
        SeatForecastResult actual = predictor.predict(new SeatDistributionInput(
            golden.featureVector()
                .stream()
                .mapToDouble(Double::doubleValue)
                .toArray(),
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

    public ModelRelease release() {
        BundleManifest manifest = coefficients.manifest();
        return new ModelRelease(manifest.releaseId(), manifest.modelVersion(), manifest.identityDigest(),
            manifest.featureContractVersion(), manifest.dataThrough(), scope(), predictor,
            BundleLoader.featuresOf(manifest), routeReference);
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

    /**
     * 기존 특징 계약은 참조 파일을 읽지 않고 null을 유지한다. 새 특징 계약에서만 참조 파일 검증·읽기를 수행한다.
     */
    private static ForecastRouteReference readRouteReference(BundleFiles files, CoefficientBundle coefficients) {
        if (BundleLoader.featuresOf(coefficients.manifest()) != ForecastFeatureContract.LEGACY) {
            return BundleRouteReference.read(files.readRouteReference(), coefficients.manifest());
        }
        return null;
    }
}
