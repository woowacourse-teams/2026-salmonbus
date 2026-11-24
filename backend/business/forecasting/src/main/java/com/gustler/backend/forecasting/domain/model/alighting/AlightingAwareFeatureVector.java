package com.gustler.backend.forecasting.domain.model.alighting;

/**
 * 좌석 예측용 88개 입력과 만석 확률 예측용 80개 입력이다.
 * 좌석 입력은 32비트 실수의 반올림을 유지한다. 확률 입력에는 같은 정밀도로 만든 시간 정보를 64비트 실수로 확장한 값이 포함된다.
 */
public record AlightingAwareFeatureVector(double[] seat88, double[] probability80) {

    public AlightingAwareFeatureVector {
        if (seat88.length != 88 || probability80.length != 80) {
            throw new IllegalArgumentException("좌석 입력은 88개, 만석 확률 입력은 80개여야 한다");
        }
        seat88 = seat88.clone();
        probability80 = probability80.clone();
    }

    @Override
    public double[] seat88() {
        return seat88.clone();
    }

    @Override
    public double[] probability80() {
        return probability80.clone();
    }
}
