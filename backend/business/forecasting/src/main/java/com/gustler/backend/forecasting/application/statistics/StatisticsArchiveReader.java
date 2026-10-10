package com.gustler.backend.forecasting.application.statistics;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 통계가 필요로 하는 이관 입력 계약. 정산 도메인의 내부 타입을 노출하지 않는다. */
public interface StatisticsArchiveReader {
    List<Row> read(Reference reference);

    record Key(long observationId, int stopOrder) { }
    record Row(Key key, String originalJson, String sha256) {
        public Row {
            Objects.requireNonNull(key);
            Objects.requireNonNull(originalJson);
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("이관 원본의 검증값이 필요하다");
            }
        }
    }
    record Reference(UUID batchId, long routeVersionId, long qualityRevision, int rowCount, String manifestSha256) {
        public Reference {
            if (batchId == null || routeVersionId <= 0 || qualityRevision <= 0 || rowCount < 1 || rowCount > 100
                || manifestSha256 == null || !manifestSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("검증 완료된 이관 묶음 정보가 필요하다");
            }
        }
    }
}
