package com.gustler.backend.forecasting.domain.statistics;

import java.time.Instant;
import java.util.List;

public record DemandSamplePage(List<PendingDemandSample> samples) {

    public DemandSamplePage {
        samples = List.copyOf(samples);
    }

    public boolean requiresRebuild() {
        return samples.stream().anyMatch(sample -> !sample.usable());
    }

    public List<PendingDemandSample> applicableUntil(final Instant dataUntil) {
        if (requiresRebuild()) {
            throw new IllegalStateException("품질을 통과하지 못한 입력이 섞인 묶음은 반영하지 않는다");
        }
        return samples.stream().filter(sample -> !sample.sample().scoredAt().isAfter(dataUntil)).toList();
    }

    public List<VehicleHourlyDemand> totalsUntil(final Instant dataUntil) {
        return VehicleHourlyDemand.sumOf(applicableUntil(dataUntil).stream().map(PendingDemandSample::sample).toList());
    }

    public long nextInputId(final long afterInputId) {
        return samples.isEmpty() ? afterInputId : samples.getLast().id();
    }

    public int size() {
        return samples.size();
    }
}
