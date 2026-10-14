package com.gustler.backend.arrivals.api;

import java.time.Instant;

public interface RetainArrivalSnapshots {
    int purgeExpiredBefore(Instant cutoff, int limit);
}
