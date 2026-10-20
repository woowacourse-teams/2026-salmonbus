package com.gustler.backend.api.route.infrastructure.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 조회 응답의 모델 식별과 학습 기준 시각을 저장된 배포 자료에서 읽는다.
 */
@Entity
@Table(name = "model_deployment")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ModelDeploymentJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "release_id", nullable = false)
    private String releaseId;

    @Column(name = "data_until", nullable = false)
    private OffsetDateTime dataUntil;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ModelDeploymentState state;

    public Long id() {
        return id;
    }

    public String releaseId() {
        return releaseId;
    }

    public OffsetDateTime dataUntil() {
        return dataUntil;
    }
}
