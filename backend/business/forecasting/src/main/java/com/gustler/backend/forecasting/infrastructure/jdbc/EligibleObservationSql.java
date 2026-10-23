package com.gustler.backend.forecasting.infrastructure.jdbc;

/**
 * 예보·정산·통계가 함께 사용할 관측 적격성 SQL 조각을 한곳에서 정의한다.
 */
final class EligibleObservationSql {
    static final String ARRIVAL_MATCHES_SOURCE = """
        arrival.quality_direction = source.quality_direction
        AND arrival.vehicle_id IS NOT DISTINCT FROM source.vehicle_id
        AND arrival.route_version_id = source.route_version_id""";

    private EligibleObservationSql() {
    }
}
