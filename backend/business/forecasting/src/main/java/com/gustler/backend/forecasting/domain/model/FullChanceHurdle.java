package com.gustler.backend.forecasting.domain.model;

/**
 * 만석 허들. 특징 벡터 하나에서 보정 전 만석 확률을 계산한다.
 *
 * <p>곱해 더한 값을 로지스틱으로 변환하기 전에 -30 부터 30 까지로 제한한다. 제한하지 않으면 확률이
 * 0 이나 1 로 포화되어 당일 보정에서 로짓으로 변환할 때 무한대가 된다.
 */
final class FullChanceHurdle {
    private final double[] coefficients;

    FullChanceHurdle(
        double[] coefficients
    ) {
        this.coefficients = coefficients.clone();
    }

    double rawFullChanceOf(
        double[] featureVector
    ) {
        return ProbabilityScale.chanceOf(LinearSum.of(featureVector, coefficients));
    }
}
