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

@Entity
@Table(name = "model_deployment")
public class ModelDeploymentJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "release_id", nullable = false, length = 80)
    private String releaseId;

    @Column(name = "data_until", nullable = false)
    private OffsetDateTime dataUntil;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 12)
    private ModelDeploymentState state;

    protected ModelDeploymentJpaEntity() {
    }

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
