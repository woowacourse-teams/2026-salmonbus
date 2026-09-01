package com.gustler.backend.api.board.persistence.jpa;

import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface SeatForecastEntityRepository
    extends Repository<SeatForecastJpaEntity, SeatForecastJpaId> {

    @Query("""
        SELECT forecast
        FROM BoardSeatForecastJpaEntity forecast
        JOIN FETCH forecast.vehicleObservation observation
        JOIN FETCH forecast.modelDeployment
        WHERE observation.observationBatch.id = :batchId
        """)
    List<SeatForecastJpaEntity> findAllByBatchId(@Param("batchId") long batchId);
}
