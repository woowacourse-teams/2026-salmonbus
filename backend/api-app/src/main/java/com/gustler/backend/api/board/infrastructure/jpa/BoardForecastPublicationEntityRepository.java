package com.gustler.backend.api.board.infrastructure.jpa;

import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 노선 판본의 예보 발행 존재 여부를 탑승판 조회에 제공하는 JPA 경계다.
 */
public interface BoardForecastPublicationEntityRepository
    extends Repository<ForecastPublicationJpaEntity, Long> {
    boolean existsByRouteVersionId(Long routeVersionId);

    /**
     * 판정보드 캐시가 같은 결과인지 가르는 판본 표시를 읽는다.
     *
     * <p>노선 품질 판본(예보 적격이 바뀌면 오른다)과 고른 관측 회차의 발행 행 중 재발행 때 바뀌는 열을 이어 붙인다.
     * 발행되지 않은 회차면 발행 부분은 '-'다. 값의 뜻은 보지 않고 같은지만 본다.
     */
    @Query(value = """
        SELECT concat_ws('|',
            'p' || COALESCE(publication.published_at::text, '-'),
            'g' || COALESCE(publication.generated_at::text, '-'),
            'n' || COALESCE(publication.prediction_count::text, '-'),
            'm' || COALESCE(publication.model_deployment_id::text, '-'),
            'd' || COALESCE(publication.demand_statistics_revision::text, '-'),
            'r' || COALESCE(publication.quality_revision::text, '-'))
        FROM route_version version

        LEFT JOIN forecast_publication publication ON publication.source_batch_id = :batchId
        WHERE version.id = :routeVersionId
        """, nativeQuery = true)
    Optional<String> findBoardRevision(@Param("routeVersionId") long routeVersionId, @Param("batchId") long batchId);
}
