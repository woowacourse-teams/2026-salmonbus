package com.gustler.backend.processor.a18;

/**
 * 만석 허들. 특징 벡터 하나에서 보정 전 만석 확률을 낸다.
 *
 * <p>곱해 더한 값을 로지스틱으로 옮기기 전에 -30 부터 30 까지로 자른다. 안 자르면 확률이
 * 0 이나 1 로 포화되고, 그다음 당일 보정이 로짓으로 옮길 때 무한대를 만난다.
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
