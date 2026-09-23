package com.gustler.backend.api.board.persistence.jpa;

import com.gustler.backend.api.route.persistence.jpa.RouteVersionJpaEntity;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface BoardObservationBatchEntityRepository
    extends Repository<ObservationBatchJpaEntity, Long> {

    @Query("""
        SELECT batch
        FROM BoardObservationBatchJpaEntity batch
        WHERE batch.routeVersion = :routeVersion
          AND batch.outcome IN ('SUCCESS_ROWS', 'SUCCESS_EMPTY')
          AND batch.responseReceivedAt IS NOT NULL
          AND EXISTS (
              SELECT publication.id
              FROM BoardForecastPublicationJpaEntity publication
              WHERE publication.sourceBatchId = batch.id
                AND publication.routeVersionId = batch.routeVersion.id
          )
        ORDER BY batch.responseReceivedAt DESC,
                 batch.id DESC
        """)
    List<ObservationBatchJpaEntity> findLatestPublished(
        @Param("routeVersion") RouteVersionJpaEntity routeVersion,
        Pageable pageable
    );
}
