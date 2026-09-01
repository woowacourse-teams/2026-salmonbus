package com.gustler.backend.api.board.persistence.jpa;

import com.gustler.backend.api.board.application.StoredObservation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity(name = "BoardVehicleObservationJpaEntity")
@Table(name = "vehicle_observation")
public class VehicleObservationJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "observation_batch_id", nullable = false)
    private ObservationBatchJpaEntity observationBatch;

    @Column(name = "source_row_number", nullable = false)
    private Integer sourceRowNumber;

    @Column(name = "vehicle_id", length = 40)
    private String vehicleId;

    @Column(name = "passed_stop_order", nullable = false)
    private Integer passedStopOrder;

    protected VehicleObservationJpaEntity() {
    }

    public StoredObservation toDomain() {
        return new StoredObservation(
            sourceRowNumber,
            vehicleId,
            passedStopOrder
        );
    }

    public Integer sourceRowNumber() {
        return sourceRowNumber;
    }

    public String vehicleId() {
        return vehicleId;
    }
}
