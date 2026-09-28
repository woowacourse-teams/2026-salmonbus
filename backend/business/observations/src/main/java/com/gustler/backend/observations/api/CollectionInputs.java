package com.gustler.backend.observations.api;

import java.time.Instant;

public interface CollectionInputs {
    void confirmInput(long batchId, Instant confirmedAt);
}
