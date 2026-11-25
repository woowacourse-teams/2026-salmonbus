package com.gustler.backend.observations.domain;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;

/** 받은 응답의 상태와 관측 값. 실패한 조회에는 정상 응답의 행 수를 기록하지 않는다. */
public record CollectionResult(
    CollectionBatchStatus outcome,
    CollectionFailureReason failureCode,
    Integer upstreamResultCode,
    Optional<ObservationRows> observations,
    OffsetDateTime receivedAt,
    Optional<String> keyRejectionCode
) {

    public CollectionResult {
        Objects.requireNonNull(observations);
        Objects.requireNonNull(keyRejectionCode);
        Objects.requireNonNull(receivedAt);

        final boolean successful = outcome == CollectionBatchStatus.SUCCESS_ROWS
            || outcome == CollectionBatchStatus.SUCCESS_EMPTY;

        if (successful != observations.isPresent()) {
            throw new IllegalArgumentException("정상 수집 응답에는 정규화한 관측 결과가 필요하다");
        }

        if (successful && keyRejectionCode.isPresent()) {
            throw new IllegalArgumentException("정상 수집 응답에는 키 거절 사유가 없다");
        }
    }

    public static CollectionResult received(
        CollectionBatchStatus outcome,
        Integer upstreamResultCode,
        ObservationRows observations,
        OffsetDateTime receivedAt
    ) {
        return new CollectionResult(
                outcome,
                null,
                upstreamResultCode,
                Optional.of(observations),
                receivedAt,
                Optional.empty()
        );
    }

    public static CollectionResult failed(
        CollectionBatchStatus outcome,
        CollectionFailureReason failureCode,
        Integer upstreamResultCode,
        OffsetDateTime receivedAt
    ) {
        return new CollectionResult(
                outcome,
                failureCode,
                upstreamResultCode,
                Optional.empty(),
                receivedAt,
                Optional.empty()
        );
    }

    public static CollectionResult failedByRejectedKey(
        CollectionBatchStatus outcome,
        CollectionFailureReason failureCode,
        Integer upstreamResultCode,
        OffsetDateTime receivedAt,
        String keyRejectionCode
    ) {
        return new CollectionResult(
                outcome,
                failureCode,
                upstreamResultCode,
                Optional.empty(),
                receivedAt,
                Optional.of(keyRejectionCode)
        );
    }

    public static CollectionResult unconfirmed(OffsetDateTime receivedAt) {
        return failed(
                CollectionBatchStatus.UNKNOWN_AFTER_DISPATCH,
                null,
                null,
                receivedAt
        );
    }
}
