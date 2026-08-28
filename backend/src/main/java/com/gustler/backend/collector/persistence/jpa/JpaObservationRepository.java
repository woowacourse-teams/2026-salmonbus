package com.gustler.backend.collector.persistence.jpa;

import com.gustler.backend.collector.ObservationBatch;
import com.gustler.backend.collector.ObservationRepository;
import com.gustler.backend.collector.UpstreamObservationRow;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class JpaObservationRepository implements ObservationRepository {

    private final CollectorObservationBatchRepository observationBatchRepository;
    private final CollectorVehicleObservationRepository vehicleObservationRepository;

    public JpaObservationRepository(
        CollectorObservationBatchRepository observationBatchRepository,
        CollectorVehicleObservationRepository vehicleObservationRepository
    ) {
        this.observationBatchRepository = observationBatchRepository;
        this.vehicleObservationRepository = vehicleObservationRepository;
    }

    @Override
    public long save(
        ObservationBatch batch,
        List<UpstreamObservationRow> storableRows
    ) {
        ObservationBatchJpaEntity savedBatch =
            observationBatchRepository.save(new ObservationBatchJpaEntity(batch));

        vehicleObservationRepository.saveAll(storableRows.stream()
            .map(row -> new VehicleObservationJpaEntity(
                savedBatch.getId(),
                batch.attempt().routeVersionId(),
                row))
            .toList());

        return savedBatch.getId();
    }
}
