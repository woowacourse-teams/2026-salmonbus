package com.gustler.backend.forecasting.domain.statistics;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 한 노선 버전의 수요를 집계한 결과와 계산 기준.
 *
 * <p>집계할 때마다 {@code revision}을 늘리고 이전 버전은 보존한다. 예보에는 사용한 통계 버전을
 * 기록한다. 표준화에 사용하는 평균과 표준편차가 통계 버전에 따라 달라지기 때문이다.
 *
 * <p>{@code dataUntil}은 집계 자료의 시간 기준이고, {@code computedAt}은 계산을 완료한 시각이다.
 * 같은 시간대의 같은 정류장에는 집계 결과를 하나만 둔다.
 */
public record DemandStatisticsVersion(
    long routeVersionId,
    String calculationVersion,
    int revision,
    Instant dataUntil,
    Instant computedAt,
    List<StopDemandMeasurement> measurements
) {

    private static final int FIRST_REVISION = 1;

    public DemandStatisticsVersion {
        if (routeVersionId <= 0) {
            throw new IllegalArgumentException("노선 버전 ID는 양수여야 한다: " + routeVersionId);
        }
        if (revision < FIRST_REVISION) {
            throw new IllegalArgumentException("통계 버전은 %d부터다: %d".formatted(FIRST_REVISION, revision));
        }
        if (calculationVersion == null || calculationVersion.isBlank()) {
            throw new IllegalArgumentException("계산 규칙 버전은 비어 있을 수 없다");
        }
        if (dataUntil == null || computedAt == null) {
            throw new IllegalArgumentException("자료 기준 시각과 계산 완료 시각이 필요하다");
        }
        if (dataUntil.isAfter(computedAt)) {
            throw new IllegalArgumentException("자료 기준 시각은 계산 완료 시각보다 늦을 수 없다");
        }
        if (measurements == null || measurements.isEmpty()) {
            throw new IllegalArgumentException("통계 버전에는 집계 결과가 하나 이상 필요하다");
        }

        Set<MeasurementKey> keys = new HashSet<>();
        for (StopDemandMeasurement measurement : measurements) {
            if (measurement == null || measurement.timeSlot() == null || measurement.cell() == null) {
                throw new IllegalArgumentException("집계 결과에는 시간대와 정류장 통계가 필요하다");
            }
            MeasurementKey key = new MeasurementKey(measurement.timeSlot(), measurement.cell().stopOrder());
            if (!keys.add(key)) {
                throw new IllegalArgumentException(
                    "같은 시간대의 정류장 통계가 중복되었다: %s, %d".formatted(key.timeSlot(), key.stopOrder())
                );
            }
        }
        measurements = List.copyOf(measurements);
    }

    private record MeasurementKey(TimeSlot timeSlot, int stopOrder) {
    }
}
