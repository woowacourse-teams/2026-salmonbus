package com.gustler.backend.forecasting.domain.model.alighting;

/** alight_fit.predict의 이항 하차 × 음이항 승차 posterior. 학습과 보정은 하지 않는다. */
public final class AlightingPosterior {
    private static final double[] GAMMA_COEFFICIENTS = {
        676.5203681218851, -1259.1392167224028, 771.32342877765313,
        -176.61502916214059, 12.507343278686905, -0.13857109526572012,
        9.9843695780195716e-6, 1.5056327351493116e-7
    };

    public record Parameters(
        double[] lambda,
        double p,
        double kappa,
        double r
    ) {
        public Parameters {
            lambda = lambda.clone();
            if (hasInvalidParameters(lambda, p, kappa, r)) {
                throw new IllegalArgumentException("하차 posterior 계수가 잘못됐다");
            }
            for (final double rate : lambda) {
                if (rate <= 0 || !Double.isFinite(rate)) {
                    throw new IllegalArgumentException("승차율은 유한한 양수여야 한다");
                }
            }
        }

        @Override
        public double[] lambda() {
            return lambda.clone();
        }

        private static boolean hasInvalidParameters(
            double[] lambda,
            final double probability,
            final double previousFullCoefficient,
            final double dispersion
        ) {
            return lambda.length != 8 || probability <= 0 || probability >= 1 || !Double.isFinite(probability)
                || previousFullCoefficient < 0 || !Double.isFinite(previousFullCoefficient)
                || dispersion <= 0 || !Double.isFinite(dispersion);
        }
    }

    private AlightingPosterior() {
    }

    public static double afterSeats(
        Parameters parameters,
        final int arrive,
        final int depart,
        final int capacity,
        final double gapMinutes,
        final boolean previousFull,
        final int bin
    ) {
        if (hasInvalidObservation(arrive, depart, capacity, gapMinutes, bin)) {
            throw new IllegalArgumentException("하차 posterior 입력이 잘못됐다");
        }
        final int onboard = capacity - arrive;
        final double mu = parameters.lambda[bin] * gapMinutes
            + parameters.kappa * fullIndicator(previousFull) + 1e-3;
        BoardingProbabilities probabilities = new BoardingProbabilities(mu, parameters.r, capacity + 1);
        return arrive + expectedAlighting(parameters, probabilities, onboard, arrive, depart);
    }

    private static boolean hasInvalidObservation(
        final int arrive,
        final int depart,
        final int capacity,
        final double gapMinutes,
        final int bin
    ) {
        return capacity < 0 || capacity > 70 || arrive < 0 || arrive > capacity || depart < 0
            || depart > capacity || gapMinutes <= 0 || !Double.isFinite(gapMinutes) || bin < 0 || bin > 7;
    }

    private static int fullIndicator(final boolean full) {
        if (full) {
            return 1;
        }
        return 0;
    }

    private static double expectedAlighting(
        Parameters parameters,
        BoardingProbabilities probabilities,
        final int onboard,
        final int arrive,
        final int depart
    ) {
        double total = 0;
        double weighted = 0;
        for (int alight = 0; alight <= onboard; alight++) {
            final double weight = Math.exp(logGamma(onboard + 1) - logGamma(alight + 1)
                - logGamma(onboard - alight + 1) + alight * Math.log(Math.max(parameters.p, 1e-9))
                + (onboard - alight) * Math.log(Math.max(1 - parameters.p, 1e-9)));
            final double chance = probabilities.observedDepartureChance(arrive, depart, alight);
            final double joint = weight * chance;
            total += joint;
            weighted += joint * alight;
        }
        return weighted / Math.max(total, 1e-300);
    }

    private static double logGamma(double value) {
        if (value < .5) {
            return Math.log(Math.PI) - Math.log(Math.sin(Math.PI * value)) - logGamma(1 - value);
        }
        value -= 1;
        double sum = .99999999999980993;
        for (int index = 0; index < GAMMA_COEFFICIENTS.length; index++) {
            sum += GAMMA_COEFFICIENTS[index] / (value + index + 1);
        }
        final double shifted = value + GAMMA_COEFFICIENTS.length - .5;
        return .5 * Math.log(2 * Math.PI) + (value + .5) * Math.log(shifted) - shifted + Math.log(sum);
    }

    /** gammaln 식, cumsum 순서, 만석의 1-cdf 계산을 원본과 동일하게 유지한다. */
    private static final class BoardingProbabilities {
        private final double[] pmf;
        private final double[] cdf;

        private BoardingProbabilities(final double mean, final double dispersion, final int maximum) {
            pmf = new double[maximum + 1];
            cdf = new double[maximum + 1];
            for (int count = 0; count <= maximum; count++) {
                pmf[count] = Math.exp(logGamma(count + dispersion) - logGamma(dispersion) - logGamma(count + 1)
                    + dispersion * Math.log(dispersion / (dispersion + mean))
                    + count * Math.log(mean / (dispersion + mean)));
                cdf[count] = pmf[count] + cumulativeBefore(count);
            }
        }

        private double cumulativeBefore(final int count) {
            if (count == 0) {
                return 0;
            }
            return cdf[count - 1];
        }

        private double observedDepartureChance(final int arrive, final int depart, final int alight) {
            if (depart == 0) {
                return atLeast(arrive + alight);
            }
            final int boarded = arrive - depart + alight;
            if (boarded < 0 || boarded >= pmf.length) {
                return 0;
            }
            return pmf[boarded];
        }

        private double atLeast(final int needed) {
            if (needed == 0) {
                return 1;
            }
            return Math.max(0, 1 - cdf[needed - 1]);
        }
    }
}
