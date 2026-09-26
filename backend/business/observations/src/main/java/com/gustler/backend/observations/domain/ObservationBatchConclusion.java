package com.gustler.backend.observations.domain;

/** 정상 응답, 외부 오류, 응답 미수신을 구분한 수집 결과. */
public record ObservationBatchConclusion(ObservationBatchOutcome outcome,
                                        ObservationBatchFailureCode failureCode,
                                        Integer upstreamResultCode) {
}
