package com.gustler.backend.api.board.persistence.jpa;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/** 원본과 별도로, 예측에 사용할 수 있는 관측을 보여 주는 읽기 전용 view. */
@Entity(name = "BoardForecastEligibleObservation")
@Table(name = "forecast_eligible_observation")
@Immutable
public class ForecastEligibleObservationJpaEntity {
    @Id
    private Long id;

    protected ForecastEligibleObservationJpaEntity() { }
}
