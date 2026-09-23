package com.gustler.backend.api.board.infrastructure.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/** 보드가 발행된 수집 배치를 고를 때 사용하는 읽기 전용 매핑. */
@Entity(name = "BoardForecastPublicationJpaEntity")
@Table(name = "forecast_publication")
@Immutable
public class ForecastPublicationJpaEntity {

    @Id
    private Long id;

    @Column(name = "source_batch_id", nullable = false)
    private Long sourceBatchId;

    @Column(name = "route_version_id", nullable = false)
    private Long routeVersionId;

    protected ForecastPublicationJpaEntity() {
    }
}
