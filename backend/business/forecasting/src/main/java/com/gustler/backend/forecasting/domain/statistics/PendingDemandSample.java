package com.gustler.backend.forecasting.domain.statistics;

import java.util.Objects;

public record PendingDemandSample(long id, DemandSample sample, boolean usable) {

    public PendingDemandSample {
        Objects.requireNonNull(sample, "통계 입력이 필요하다");
    }
}
