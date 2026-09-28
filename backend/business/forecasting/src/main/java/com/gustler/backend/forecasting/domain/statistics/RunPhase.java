package com.gustler.backend.forecasting.domain.statistics;

public enum RunPhase {
    STALE,
    CLEAN,
    CAPTURE,
    ACCUMULATE,
    FOLD,
    REDUCE,
    PUBLISH,
    DONE
}
