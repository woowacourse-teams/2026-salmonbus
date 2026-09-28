package com.gustler.backend.forecasting.infrastructure.jdbc;

final class EligibleObservationSql {

    static final String ARRIVAL_MATCHES_SOURCE = """
        arrival.quality_direction = source.quality_direction
        AND arrival.vehicle_id IS NOT DISTINCT FROM source.vehicle_id
        AND arrival.route_version_id = source.route_version_id""";

    private EligibleObservationSql() {
    }
}
