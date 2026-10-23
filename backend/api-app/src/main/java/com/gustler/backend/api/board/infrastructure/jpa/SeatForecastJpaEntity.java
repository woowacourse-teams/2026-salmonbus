package com.gustler.backend.api.board.infrastructure.jpa;

import com.gustler.backend.api.board.application.StoredPrediction;
import com.gustler.backend.api.board.domain.ForecastModel;
import com.gustler.backend.api.route.infrastructure.jpa.ModelDeploymentJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.ZoneId;

/**
 * 탑승판에 필요한 예보 행을 읽기 모델로 변환한다.
 * 예보의 부재와 제공할 수 없는 수치를 구분한다.
 */
@Entity(name = "BoardSeatForecastJpaEntity")
@Table(name = "seat_forecast")
public class SeatForecastJpaEntity {
    @EmbeddedId
    private SeatForecastJpaId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "publication_id", nullable = false)
    private ForecastPublicationJpaEntity publication;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "vehicle_observation_id",
        insertable = false,
        updatable = false,
        nullable = false
    )
    private VehicleObservationJpaEntity vehicleObservation;

    @Column(name = "stops_to_target", nullable = false)
    private Integer stopsToTarget;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_deployment_id", nullable = false)
    private ModelDeploymentJpaEntity modelDeployment;

    @Column(name = "seat_full_chance", nullable = false)
    private Double seatFullChance;

    @Column(name = "expected_seats")
    private Double expectedSeats;

    protected SeatForecastJpaEntity() {
    }

    public StoredPrediction toDomain(
        ZoneId zoneId
    ) {
        return new StoredPrediction(
            id.targetStopOrder(),
            vehicleObservation.vehicleId(),
            vehicleObservation.sourceRowNumber(),
            stopsToTarget,
            seatFullChance,
            expectedSeats,
            new ForecastModel(
                modelDeployment.id(),
                modelDeployment.releaseId(),
                modelDeployment.dataUntil()
                    .atZoneSameInstant(zoneId)
                    .toOffsetDateTime()
            )
        );
    }
}
