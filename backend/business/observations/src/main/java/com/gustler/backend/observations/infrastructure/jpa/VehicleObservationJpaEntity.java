package com.gustler.backend.observations.infrastructure.jpa;

import com.gustler.backend.observations.domain.SeatUnknownReason;
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
 * Worker 기동 시 관측 열과 타입을 검증하기 위한 매핑이다. 실제 저장, 삭제는 JDBC 저장소가 수행한다.
 * 저장용 임시 객체로 생성하거나 저장 결과를 복사하지 않는다.
 *
 * <p>조회 쪽도 이 표를 매핑한다. 같은 물리 컬럼에 논리명이 둘이면 Hibernate 가
 * DuplicateMappingException 을 발생시켜 앱이 기동하지 않는다. 따라서 모든 필드에 @Column(name) 을 명시한다.
 *
 * <p>vehicle_trip_key 는 수집 엔티티에 매핑하지 않는다. 이상 차량의 제한된 조사에서만
 * 이 nullable 파생 열에 편도 키를 기록한다.
 */
@Entity(name = "VehicleObservation")
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

}
