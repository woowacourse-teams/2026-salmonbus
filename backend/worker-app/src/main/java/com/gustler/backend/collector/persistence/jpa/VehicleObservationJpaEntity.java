package com.gustler.backend.collector.persistence.jpa;

import com.gustler.backend.collector.RemainingSeats;
import com.gustler.backend.collector.RemainingSeats.Known;
import com.gustler.backend.collector.RemainingSeats.Unknown;
import com.gustler.backend.collector.SeatUnknownReason;
import com.gustler.backend.collector.UpstreamObservationRow;
import com.gustler.backend.collector.VehicleObservation;
import jakarta.persistence.Column;
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
 * 조회 쪽도 이 표를 매핑한다. 같은 물리 컬럼에 논리명이 둘이면 Hibernate 가
 * DuplicateMappingException 을 내고 앱이 안 뜬다. 그래서 전 필드에 @Column(name) 을 명시한다.
 *
 * <p>vehicle_trip_key 는 매핑하지 않는다. 여정을 어디서 가를지 문턱값이 아직 없어서
 * 지금 채우면 뜻이 없는 값이 들어간다. 열은 NULL 허용으로 이미 있다.
 */
@Entity(name = "CollectorVehicleObservation")
@Table(name = "vehicle_observation")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VehicleObservationJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "observation_batch_id")
    private Long observationBatchId;

    @Column(name = "route_version_id")
    private Long routeVersionId;

    @Column(name = "source_row_number")
    private Integer sourceRowNumber;

    @Column(name = "vehicle_id")
    private String vehicleId;

    @Column(name = "plate_number")
    private String plateNumber;

    @Column(name = "stop_order")
    private Integer stopOrder;

    @Column(name = "stop_id")
    private String stopId;

    @Column(name = "passed_stop_order")
    private Integer passedStopOrder;

    @Column(name = "running_state")
    private Integer runningState;

    @Column(name = "remaining_seats")
    private Integer remainingSeats;

    @Enumerated(EnumType.STRING)
    @Column(name = "seat_unknown_reason")
    private SeatUnknownReason seatUnknownReason;

    @Column(name = "crowd_level")
    private Integer crowdLevel;

    @Column(name = "vehicle_type")
    private Integer vehicleType;

    @Column(name = "route_type")
    private Integer routeType;

    @Column(name = "tagless")
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
