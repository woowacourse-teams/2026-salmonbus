package com.gustler.backend.collector.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CollectorVehicleObservationRepository extends JpaRepository<VehicleObservationJpaEntity, Long> {
}
