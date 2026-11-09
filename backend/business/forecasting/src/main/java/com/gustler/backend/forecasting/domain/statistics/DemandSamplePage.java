package com.gustler.backend.forecasting.domain.statistics;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 한 처리 범위의 통계 표본과 다음 입력 위치를 보관한다.
 */
public record DemandSamplePage(List<DemandSamplePage.PendingSample> samples) {
    public record PendingSample(long id, DemandSample sample, boolean usable) {
        public PendingSample {
            Objects.requireNonNull(sample, "통계 입력이 필요하다");
        }
    }

    public DemandSamplePage {
        samples = List.copyOf(samples);
    }

    public List<DemandSamplePage.PendingSample> applicableUntil(Instant dataUntil) {
        return processedUntil(dataUntil).stream().filter(DemandSamplePage.PendingSample::usable).toList();
    }

    /** 기준 시각까지 확정된 표본은 적격 여부와 관계없이 대기열에서 처리한다. */
    public List<DemandSamplePage.PendingSample> processedUntil(Instant dataUntil) {
        return samples.stream().filter(sample -> isScoredBy(sample, dataUntil)).toList();
    }

    private boolean isScoredBy(DemandSamplePage.PendingSample pending, Instant dataUntil) {
        DemandSample sample = pending.sample();
        return !sample.scoredAt().isAfter(dataUntil);
    }

    public List<VehicleHourlyDemand> totalsUntil(Instant dataUntil) {
        return VehicleHourlyDemand
            .sumOf(applicableUntil(dataUntil).stream().map(DemandSamplePage.PendingSample::sample).toList());
    }

    public long nextInputId(final long afterInputId) {
        if (samples.isEmpty()) {
            return afterInputId;
        }
        return samples.getLast().id();
    }

    public int size() {
        return samples.size();
    }
}
