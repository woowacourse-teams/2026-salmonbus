package com.gustler.backend.forecasting.domain.model.alighting;

/** 하차 후 좌석 점예측과 출발 만석 확률을 별도로 계산한다. */
public final class AlightingAwareSeatPredictor {

    /** 원본과 계산 결과를 대조할 때 사용할 중간 좌석 평균과 최종 예측값이다. */
    public record Prediction(
        double base,
        double gateZero,
        double gateLow,
        double gateOther,
        double afterAlightingSeats,
        double departureFullProbability
    ) {

    }

    private final NumericTreeEnsemble base;
    private final NumericTreeEnsemble gate;
    private final NumericTreeEnsemble pool;
    private final double middle;

    public AlightingAwareSeatPredictor(
        NumericTreeEnsemble base,
        NumericTreeEnsemble gate,
        NumericTreeEnsemble pool,
        final double middle
    ) {
        validateSeatModels(base, gate);
        validateProbabilityModel(pool);
        validateMiddleSeats(middle);
        this.base = base;
        this.gate = gate;
        this.pool = pool;
        this.middle = middle;
    }

    private static void validateSeatModels(NumericTreeEnsemble base, NumericTreeEnsemble gate) {
        if (hasInvalidSeatModels(base, gate)) {
            throw invalidModel();
        }
    }

    private static boolean hasInvalidSeatModels(NumericTreeEnsemble base, NumericTreeEnsemble gate) {
        return base.kind() != NumericTreeEnsemble.Kind.REGRESSION || base.featureCount() != 88
            || gate.kind() != NumericTreeEnsemble.Kind.MULTICLASS || gate.featureCount() != 88;
    }

    private static void validateProbabilityModel(NumericTreeEnsemble pool) {
        if (pool.kind() != NumericTreeEnsemble.Kind.BINARY || pool.featureCount() != 80) {
            throw invalidModel();
        }
    }

    private static void validateMiddleSeats(final double middle) {
        if (hasInvalidMiddleSeats(middle)) {
            throw invalidModel();
        }
    }

    private static boolean hasInvalidMiddleSeats(final double middle) {
        return !Double.isFinite(middle) || middle < 1 || middle > 2;
    }

    private static IllegalArgumentException invalidModel() {
        return new IllegalArgumentException("하차 반영 좌석 모델 설정 또는 중간 좌석 평균이 올바르지 않다");
    }

    public Prediction predict(double[] seatFeatures, double[] probabilityFeatures) {
        double[] rounded = roundSeatFeatures(seatFeatures);
        final double point = Math.max(0.0, base.predict(rounded)[0]);
        double[] classProbabilities = gate.predict(rounded);
        final double seats = Math.max(0.0, Math.min(70.0,
            classProbabilities[1] * middle + classProbabilities[2] * point));
        return new Prediction(
                point,
                classProbabilities[0],
                classProbabilities[1],
                classProbabilities[2],
                seats,
                pool.predict(probabilityFeatures)[1]
        );
    }

    /** 좌석 입력만 원본 float32로 반올림한다. 확률 입력의 float64 계약은 유지한다. */
    private static double[] roundSeatFeatures(double[] seatFeatures) {
        double[] rounded = seatFeatures.clone();
        for (int index = 0; index < rounded.length; index++) {
            rounded[index] = (double) (float) rounded[index];
        }
        return rounded;
    }
}
