package com.gustler.backend.api.board.persistence.jpa;

import com.gustler.backend.api.route.persistence.jpa.RouteVersionJpaEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface ObservationBatchEntityRepository
    extends Repository<ObservationBatchJpaEntity, Long> {

    @Query("""
        SELECT batch
        FROM BoardObservationBatchJpaEntity batch
        WHERE batch.routeVersion = :routeVersion
          AND batch.outcome IN :outcomes
          AND batch.forecastCompletedAt IS NOT NULL
          AND batch.responseReceivedAt IS NOT NULL
        ORDER BY batch.responseReceivedAt DESC,
                 batch.id DESC
        """)
    List<ObservationBatchJpaEntity> findLatestCompleted(
        @Param("routeVersion") RouteVersionJpaEntity routeVersion,
        @Param("outcomes") Collection<String> outcomes,
        Pageable pageable
    );
}
