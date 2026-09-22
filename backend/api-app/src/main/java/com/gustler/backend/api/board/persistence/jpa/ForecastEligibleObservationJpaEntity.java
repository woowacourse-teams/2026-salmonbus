package com.gustler.backend.api.board.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

@Entity(name = "BoardForecastEligibleObservation")
@Table(name = "forecast_eligible_observation")
@Immutable
public class ForecastEligibleObservationJpaEntity {
    @Id
    private Long id;

    @Column(name = "observation_batch_id")
    private Long observationBatchId;

    protected ForecastEligibleObservationJpaEntity() { }
}
