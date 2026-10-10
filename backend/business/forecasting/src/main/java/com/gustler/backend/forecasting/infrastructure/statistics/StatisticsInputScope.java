package com.gustler.backend.forecasting.infrastructure.statistics;

import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsVersion;
import java.time.Instant;
import java.time.ZoneId;

record StatisticsInputScope(
    String schemaVersion,
    String calculationVersion,
    long routeVersionId,
    long qualityRevision,
    Instant dataUntil,
    ZoneId zone,
    long rowCount
) {
    static final String SCHEMA_VERSION = "statistics-input-v1";

    StatisticsInputScope {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("지원하지 않는 자료 형식이다");
        }
        if (!DemandStatisticsVersion.CURRENT_CALCULATION_VERSION.equals(calculationVersion)) {
            throw new IllegalArgumentException("지원하지 않는 통계 계산 규칙이다");
        }
        if (routeVersionId <= 0 || qualityRevision < 1 || rowCount < 0) {
            throw new IllegalArgumentException("노선 버전, 품질 판본, 자료 건수가 올바르지 않다");
        }
        if (dataUntil == null || zone == null) {
            throw new IllegalArgumentException("계산 기준 시각과 시간대가 필요하다");
        }
    }
}
