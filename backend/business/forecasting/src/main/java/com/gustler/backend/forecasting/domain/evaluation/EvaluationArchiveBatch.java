package com.gustler.backend.forecasting.domain.evaluation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 소유권 토큰은 재시작한 작업자가 이전 실행자의 결과 확정을 막는 데 사용한다. */
public record EvaluationArchiveBatch(UUID id, long routeVersionId, long qualityRevision,
    UUID leaseToken, Instant leaseUntil, State state, int rowCount, String manifestSha256) {

    public EvaluationArchiveBatch {
        Objects.requireNonNull(id);
        Objects.requireNonNull(leaseToken);
        Objects.requireNonNull(leaseUntil);
        Objects.requireNonNull(state);
        if (routeVersionId <= 0 || qualityRevision <= 0 || rowCount < 1 || rowCount > 100) {
            throw new IllegalArgumentException("이관 묶음의 노선·품질 판본·건수가 올바르지 않다");
        }
        if (state == State.VERIFIED ? manifestSha256 == null || !manifestSha256.matches("[0-9a-f]{64}")
            : manifestSha256 != null) {
            throw new IllegalArgumentException("검증 상태와 파일 검증값이 일치하지 않는다");
        }
    }

    public enum State { RESERVED, VERIFIED }

    public record Key(long observationId, int stopOrder) {
        public Key {
            if (observationId <= 0 || stopOrder <= 0) {
                throw new IllegalArgumentException("정산을 식별하는 관측과 정류장이 필요하다");
            }
        }
    }

    public record Row(Key key, String originalJson, String sha256) {
        public Row {
            Objects.requireNonNull(key);
            Objects.requireNonNull(originalJson);
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("원본 검증값이 올바르지 않다");
            }
        }
    }
}
