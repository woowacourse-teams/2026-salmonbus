package com.gustler.backend.processor.seatdistribution;

/**
 * 특징 벡터와 계수를 곱해 더한다.
 *
 * <p>더하는 순서가 구현 계약이다. 순서를 바꾸면 부동소수점 반올림이 달라져 파이썬 정본과
 * 마지막 자리가 어긋난다. 앞에서부터 차례로 더한다.
 */
final class LinearSum {

    private LinearSum() {
    }

    static double of(
        double[] featureVector,
        double[] coefficients
    ) {
        if (featureVector.length != coefficients.length) {
            throw new IllegalArgumentException(
                "특징 개수와 계수 개수가 같아야 한다: %d, %d"
                    .formatted(featureVector.length, coefficients.length)
            );
        }
        double sum = 0.0;
        for (int index = 0; index < featureVector.length; index++) {
            sum += featureVector[index] * coefficients[index];
        }
        return sum;
    }
}
