package com.gustler.backend.forecasting.domain.model.les;

/** 하차 후 좌석 점예측과 출발 만석 확률을 별도로 계산한다. */
public final class LesSeatPredictor {
    public record Prediction(double base, double gateZero, double gateLow, double gateOther,
                             double afterAlightingSeats, double departureFullProbability) {
    }

    private final NumericTreeEnsemble base;
    private final NumericTreeEnsemble gate;
    private final NumericTreeEnsemble pool;
    private final double middle;

    public LesSeatPredictor(NumericTreeEnsemble base, NumericTreeEnsemble gate,
                            NumericTreeEnsemble pool, double middle) {
        if (base.kind() != NumericTreeEnsemble.Kind.REGRESSION || base.featureCount() != 88
            || gate.kind() != NumericTreeEnsemble.Kind.MULTICLASS || gate.featureCount() != 88
            || pool.kind() != NumericTreeEnsemble.Kind.BINARY || pool.featureCount() != 80
            || !Double.isFinite(middle) || middle < 1 || middle > 2) {
            throw new IllegalArgumentException("레스 모델 구성이나 중간 좌석 평균이 잘못됐다");
        }
        this.base = base;
        this.gate = gate;
        this.pool = pool;
        this.middle = middle;
    }

    public Prediction predict(double[] seatFeatures, double[] probabilityFeatures) {
        double[] rounded = seatFeatures.clone();
        for (int i = 0; i < rounded.length; i++) {
            rounded[i] = (double) (float) rounded[i];
        }
        double point = Math.max(0.0, base.predict(rounded)[0]);
        double[] p = gate.predict(rounded);
        double seats = Math.max(0.0, Math.min(70.0, p[1] * middle + p[2] * point));
        return new Prediction(point, p[0], p[1], p[2], seats, pool.predict(probabilityFeatures)[1]);
    }
}
