package com.gustler.backend.forecasting.domain.statistics;

import java.time.Instant;
import java.util.Optional;

public record DemandStatisticsBaseline(boolean initialized, Instant dataUntil) {

    public Instant fixUntil(final Instant candidate) {
        return dataUntil != null && dataUntil.isAfter(candidate) ? dataUntil : candidate;
    }

    public Optional<Instant> recordedUntil() {
        return Optional.ofNullable(dataUntil);
    }
}
