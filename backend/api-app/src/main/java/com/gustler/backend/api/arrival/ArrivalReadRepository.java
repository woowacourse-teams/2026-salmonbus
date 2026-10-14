package com.gustler.backend.api.arrival;

import com.gustler.backend.arrival.ArrivalSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ArrivalReadRepository {
    List<Visit> visits(String stationId);
    Optional<ArrivalSnapshot> latest(String stationId);

    record Visit(String routeId, String sourceRouteId, long referenceVersionId, int stopSequence,
                 String direction, Instant validFrom) { }
}
