package com.gustler.backend.processor.seatdistribution;

import com.gustler.backend.processor.SeatForecastResult;
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
