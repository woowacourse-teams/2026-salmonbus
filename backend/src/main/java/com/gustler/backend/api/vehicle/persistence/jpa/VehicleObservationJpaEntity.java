package com.gustler.backend.api.vehicle.persistence.jpa;

import com.gustler.backend.api.vehicle.domain.ObservedVehicle;
import com.gustler.backend.api.vehicle.domain.VehiclePhase;
import com.gustler.backend.api.vehicle.domain.VehicleSeat;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "vehicle_observation")
public class VehicleObservationJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "observation_batch_id", nullable = false)
    private ObservationBatchJpaEntity observationBatch;

    @Column(name = "route_version_id", nullable = false)
    private Long routeVersionId;

    @Column(name = "source_row_number", nullable = false)
    private Integer sourceRowNumber;

    @Column(name = "vehicle_id", length = 40)
    private String vehicleId;

    @Column(name = "stop_order", nullable = false)
    private Integer stopOrder;

    @Column(name = "stop_id", nullable = false, length = 20)
    private String stopId;

    @Column(name = "running_state")
    private Integer runningState;

    @Column(name = "remaining_seats")
    private Integer remainingSeats;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
        @JoinColumn(
            name = "route_version_id",
            referencedColumnName = "route_version_id",
            insertable = false,
            updatable = false
        ),
        @JoinColumn(
            name = "stop_order",
            referencedColumnName = "stop_order",
            insertable = false,
            updatable = false
        )
    })
    private RouteStopJpaEntity routeStop;

    protected VehicleObservationJpaEntity() {
    }

    public ObservedVehicle toDomain() {
        return new ObservedVehicle(
            vehicleId,
            routeStop.direction(),
            stopOrder,
            stopId,
            routeStop.name(),
            VehiclePhase.fromRunningState(runningState),
            VehicleSeat.from(remainingSeats)
        );
    }
}
