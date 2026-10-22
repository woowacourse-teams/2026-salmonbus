package com.gustler.backend.api.board.infrastructure.jpa;

import com.gustler.backend.api.board.application.BoardVehicleObservation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 수집 배치의 원천 행 식별을 유지하며 저장된 차량 관측을 조회 모델로 옮기는 JPA 매핑이다.
 */
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

    @Column(name = "route_version_id", nullable = false)
    private Long routeVersionId;

    @Column(name = "stop_order", nullable = false)
    private Integer stopOrder;

    @Column(name = "passed_stop_order", nullable = false)
    private Integer passedStopOrder;

    protected VehicleObservationJpaEntity() {
    }

    public Integer sourceRowNumber() {
        return sourceRowNumber;
    }

    public String vehicleId() {
        return vehicleId;
    }

    public BoardVehicleObservation toDomain() {
        return new BoardVehicleObservation(vehicleId, sourceRowNumber, passedStopOrder, stopOrder);
    }
}
