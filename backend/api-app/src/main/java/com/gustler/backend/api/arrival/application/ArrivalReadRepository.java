package com.gustler.backend.api.arrival.application;

import com.gustler.backend.arrival.contract.ArrivalSnapshot;
import com.gustler.backend.api.arrival.domain.ArrivalVisit;
import java.util.List;
import java.util.Optional;

public interface ArrivalReadRepository {
    List<ArrivalVisit> visits(String stationId);
    Optional<ArrivalSnapshot> latest(String stationId);
}
