package com.gustler.backend.forecasting.domain.model.les;

/** Seat inputs retain float32; pool inputs contain the float32 X_time values widened to float64. */
public record LesFeatureVector(double[] seat88, double[] probability80) {
    public LesFeatureVector {
        if (seat88.length != 88 || probability80.length != 80) {
            throw new IllegalArgumentException("Les feature width differs");
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
