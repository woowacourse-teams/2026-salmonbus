package com.gustler.backend.api.board.infrastructure.jpa;

import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface SeatForecastEntityRepository
    extends Repository<SeatForecastJpaEntity, SeatForecastJpaId> {

    @Query("""
        SELECT forecast
        FROM BoardSeatForecastJpaEntity forecast
        JOIN forecast.publication publication
        JOIN FETCH forecast.vehicleObservation observation
        JOIN FETCH forecast.modelDeployment
        WHERE publication.sourceBatchId = :batchId
          AND observation.observationBatch.id = :batchId
          AND EXISTS (SELECT eligible.id FROM BoardForecastEligibleObservation eligible
                      WHERE eligible.id = observation.id AND eligible.observationBatchId = :batchId)
        """)
    List<SeatForecastJpaEntity> findAllByBatchId(@Param("batchId") long batchId);
}
