package com.gustler.backend.forecasting.domain.publication;

import com.gustler.backend.forecasting.domain.model.ForecastDistance;
import com.gustler.backend.forecasting.domain.model.SeatForecastResult;
import com.gustler.backend.forecasting.domain.model.VehicleStopTarget;
import java.time.Instant;

/**
 * 저장할 예보 한 줄. 한 관측이 한 정류장에 도착할 때의 좌석 예보다.
 *
 * <p>발행한 예측값을 보관하며 평가 결과는 담지 않는다.
 * 후속 관측으로 확인한 평가는 별도의 ForecastEvaluation에 저장한다.
 */
public record SeatForecast(
    long vehicleObservationId,
    long routeVersionId,
    int targetStopOrder,
    int stopsToTarget,
    long modelDeploymentId,
    int demandStatisticsRevision,
    double seatFullChanceRaw,
    double seatFullChance,
    Double expectedSeats,
    Instant generatedAt,
    boolean evaluationDetailRequired,
    String seatTarget,
    String probabilityTarget,
    String labelVersion,
    String featureSnapshot,
    String inferenceBundleHash
) {
    public SeatForecast(
        final long vehicleObservationId,
        final long routeVersionId,
        final int targetStopOrder,
        final int stopsToTarget,
        final long modelDeploymentId,
        final int demandStatisticsRevision,
        final double seatFullChanceRaw,
        final double seatFullChance,
        Double expectedSeats,
        Instant generatedAt,
        final boolean evaluationDetailRequired) {
        this(vehicleObservationId, routeVersionId, targetStopOrder, stopsToTarget, modelDeploymentId,
            demandStatisticsRevision, seatFullChanceRaw, seatFullChance, expectedSeats, generatedAt,
            evaluationDetailRequired, SeatForecastResult.LEGACY_SEAT_TARGET,
            SeatForecastResult.LEGACY_PROBABILITY_TARGET, null, null, null);
    }

    public SeatForecast(
        final long vehicleObservationId,
        final long routeVersionId,
        final int targetStopOrder,
        final int stopsToTarget,
        final long modelDeploymentId,
        final int demandStatisticsRevision,
        final double seatFullChanceRaw,
        final double seatFullChance,
        Double expectedSeats,
        Instant generatedAt
    ) {
        this(vehicleObservationId, routeVersionId, targetStopOrder, stopsToTarget, modelDeploymentId,
            demandStatisticsRevision, seatFullChanceRaw, seatFullChance, expectedSeats, generatedAt, true);
    }

    public SeatForecast {
        if (!ForecastDistance.covers(stopsToTarget)) {
            throw new IllegalArgumentException("예보는 1정류장 앞부터 12정류장 앞까지만 낸다: " + stopsToTarget);
        }
        if (expectedSeats != null && !(expectedSeats >= 0)) {
            throw new IllegalArgumentException("기대 잔여석은 0석 이상이다: " + expectedSeats);
        }
        if (generatedAt == null) {
            throw new IllegalArgumentException("예보에는 계산 시각이 있어야 한다");
        }
    }

    /**
     * 발행한 예보를 저장 형식으로 변환한다.
     *
     * <p>예보 거리는 대상 순번에서 관측 차량이 지나온 순번을 뺀 값이며 대상 객체가 이미 계산한다.
     * 여기서 다시 계산하면 두 구현의 결과가 달라질 수 있다.
     */
    public static SeatForecast of(
        final long vehicleObservationId,
        VehicleStopTarget target,
        SeatForecastResult result,
        final long modelDeploymentId,
        final int demandStatisticsRevision,
        Instant generatedAt) {
        final long routeVersionId = target.observation().routeVersionId();
        final int stopsToTarget = target.distance().stopCount();
        Double expectedSeats = result.expectedSeats();

        return new SeatForecast(
            vehicleObservationId,
            routeVersionId,
            target.stopOrder(),
            stopsToTarget,
            modelDeploymentId,
            demandStatisticsRevision,
            result.fullChanceRaw(),
            result.fullChance(),
            expectedSeats,
            generatedAt, true, result.seatTarget(), result.probabilityTarget(), null, null, null);
    }

    public SeatForecast withLesEvidence(String snapshot, String bundleHash) {
        return withLesEvidence(snapshot, bundleHash, "les-identity-safe-v1");
    }

    public SeatForecast withLesEvidence(String snapshot, String bundleHash, String labelVersion) {
        if (!hasLesEvidence(snapshot, bundleHash)) {
            throw new IllegalArgumentException("레스 예보에는 입력과 계수 근거가 있어야 한다");
        }
        return new SeatForecast(vehicleObservationId, routeVersionId, targetStopOrder, stopsToTarget,
            modelDeploymentId, demandStatisticsRevision, seatFullChanceRaw, seatFullChance, expectedSeats,
            generatedAt, evaluationDetailRequired, seatTarget, probabilityTarget, labelVersion,
            snapshot, bundleHash);
    }

    public SeatForecast withEvaluationDetailRequired(final boolean required) {
        return new SeatForecast(vehicleObservationId, routeVersionId, targetStopOrder, stopsToTarget,
            modelDeploymentId, demandStatisticsRevision, seatFullChanceRaw, seatFullChance, expectedSeats,
            generatedAt, required, seatTarget, probabilityTarget, labelVersion, featureSnapshot, inferenceBundleHash);
    }

    private boolean hasLesEvidence(String snapshot, String bundleHash) {
        return SeatForecastResult.LES_SEAT_TARGET.equals(seatTarget) && snapshot != null && bundleHash != null;
    }

}
