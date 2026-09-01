package com.gustler.backend.api.board.application;

import com.gustler.backend.api.board.domain.BoardStop;
import com.gustler.backend.api.route.RouteId;
import java.util.List;
import java.util.Optional;

public interface BoardQueryRepository {

    Optional<BoardSnapshot> findSnapshot(RouteId routeId);

    List<BoardStop> findStops(long routeVersionId);

    List<StoredPrediction> findPredictions(long observationBatchId);

    List<StoredObservation> findObservations(long observationBatchId);
}
