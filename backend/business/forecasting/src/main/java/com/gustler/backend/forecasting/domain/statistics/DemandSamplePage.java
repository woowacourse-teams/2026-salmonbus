package com.gustler.backend.forecasting.domain.statistics;

import com.gustler.backend.forecasting.domain.statistics.DemandSamplePage.PendingSample;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 한 처리 범위의 통계 표본과 재집계 필요 여부·다음 입력 위치를 보관한다.
 */
public record DemandSamplePage(List<PendingSample> samples) {
    public record PendingSample(long id, DemandSample sample, boolean usable) {
        public PendingSample {
            Objects.requireNonNull(sample, "통계 입력이 필요하다");
        }
    }

    public DemandSamplePage {
        samples = List.copyOf(samples);
    }

    public boolean requiresRebuild() {
        return samples.stream().anyMatch(sample -> !sample.usable());
    }

    /**
     * 품질 검증을 통과한 페이지에서 기준 시각까지 확정된 표본만 고른다. 재구축이 필요한 페이지는 예외로 거부한다.
     */
    public List<PendingSample> applicableUntil(Instant dataUntil) {
        if (requiresRebuild()) {
            throw new IllegalStateException("품질을 통과하지 못한 입력이 섞인 묶음은 반영하지 않는다");
        }
        return samples.stream()
            .filter(sample -> isScoredBy(sample, dataUntil))
            .toList();
    }

    private boolean isScoredBy(PendingSample pending, Instant dataUntil) {
        DemandSample sample = pending.sample();
        return !sample.scoredAt().isAfter(dataUntil);
    }

    public List<VehicleHourlyDemand> totalsUntil(Instant dataUntil) {
        return VehicleHourlyDemand.sumOf(applicableUntil(dataUntil).stream()
            .map(PendingSample::sample)
            .toList());
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
