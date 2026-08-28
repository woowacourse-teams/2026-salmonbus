package com.gustler.backend.api.route.persistence.jpa;

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
