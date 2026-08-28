package com.gustler.backend.collector;

import java.util.List;

public interface ObservationRepository {

    long save(
        ObservationBatch batch,
        List<UpstreamObservationRow> storableRows
    );
}
