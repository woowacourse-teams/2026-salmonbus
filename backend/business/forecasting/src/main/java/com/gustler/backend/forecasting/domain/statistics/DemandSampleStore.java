package com.gustler.backend.forecasting.domain.statistics;

import java.util.List;

public interface DemandSampleStore {

    void record(List<DemandSample> samples);

    DemandSamplePage lockPage(long routeVersionId, String vehicleId, long afterInputId, long inputUntilId, int limit);

    void remove(List<Long> sampleIds);

    long lastSampleId();
}
