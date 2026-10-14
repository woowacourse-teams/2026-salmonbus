package com.gustler.backend.arrivals.domain;

import com.gustler.backend.arrival.contract.ArrivalSnapshot;
import java.time.Instant;
import java.util.Optional;

public interface ArrivalStore {
    boolean supported(String stationId);
    Optional<ArrivalSnapshot> latest(String stationId);
    void save(ArrivalSnapshot snapshot);
    int purgeExpiredBefore(Instant cutoff, int limit);
}
