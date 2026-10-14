package com.gustler.backend.arrivals.application;

import com.gustler.backend.arrival.ArrivalSnapshot;
import java.time.Instant;
import java.util.Optional;

public interface ArrivalStore {
    boolean supported(String stationId);
    Optional<ArrivalSnapshot> latest(String stationId);
    void save(ArrivalSnapshot snapshot);
    int purgeExpiredBefore(Instant cutoff, int limit);
}
