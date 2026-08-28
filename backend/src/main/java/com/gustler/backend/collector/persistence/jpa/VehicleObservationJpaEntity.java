package com.gustler.backend.collector.persistence.jpa;

import com.gustler.backend.collector.RemainingSeats;
import com.gustler.backend.collector.RemainingSeats.Known;
import com.gustler.backend.collector.RemainingSeats.Unknown;
import com.gustler.backend.collector.SeatUnknownReason;
import com.gustler.backend.collector.UpstreamObservationRow;
import com.gustler.backend.collector.VehicleObservation;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * vehicle_trip_key 는 매핑하지 않는다. 여정을 어디서 가를지 문턱값이 아직 없어서
 * 지금 채우면 뜻이 없는 값이 들어간다. 열은 NULL 허용으로 이미 있다.
 */
@Entity(name = "CollectorVehicleObservation")
@Table(name = "vehicle_observation")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VehicleObservationJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long observationBatchId;

    private Long routeVersionId;

    private Integer sourceRowNumber;

    private String vehicleId;

    private String plateNumber;

    private Integer stopOrder;

    private String stopId;

    private Integer passedStopOrder;

    private Integer runningState;

    private Integer remainingSeats;

    @Enumerated(EnumType.STRING)
    private SeatUnknownReason seatUnknownReason;

    private Integer crowdLevel;

    private Integer vehicleType;

    private Integer routeType;

    private Integer tagless;

    public VehicleObservationJpaEntity(
        final long observationBatchId,
        final long routeVersionId,
        UpstreamObservationRow row
    ) {
        VehicleObservation observation = row.observation();
        this.observationBatchId = observationBatchId;
        this.routeVersionId = routeVersionId;
        this.sourceRowNumber = row.sourceRowNumber();
        this.vehicleId = observation.vehicleId();
        this.plateNumber = observation.plateNumber();
        this.stopOrder = observation.stopSequence();
        this.stopId = observation.stopId();
        this.passedStopOrder = observation.passedStopOrder();
        this.runningState = observation.runningState();
        this.crowdLevel = observation.crowdLevel();
        this.vehicleType = observation.vehicleType();
        this.routeType = observation.routeType();
        this.tagless = observation.tagless();
        applySeats(observation.remainingSeats());
    }

    /** 잔여석은 아는 값이거나 모르는 사유다. 둘 중 하나만 채운다. DB 도 CHECK 로 같은 것을 막는다. */
    private void applySeats(
        RemainingSeats seats
    ) {
        switch (seats) {
            case Known known -> {
                this.remainingSeats = known.seats();
                this.seatUnknownReason = null;
            }
            case Unknown unknown -> {
                this.remainingSeats = null;
                this.seatUnknownReason = unknown.reason();
            }
        }
    }
}
