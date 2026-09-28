package com.gustler.backend.forecasting.application.statistics;

public record AccumulationResult(int selected, int applied, boolean waitingForRebuild, long nextInputId) {

    static AccumulationResult waiting(final long afterInputId) {
        return new AccumulationResult(0, 0, true, afterInputId);
    }
}
