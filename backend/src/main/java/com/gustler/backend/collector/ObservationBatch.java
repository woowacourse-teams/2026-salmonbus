package com.gustler.backend.collector;

/** 저장할 한 판의 기록. 그 판에서 상류가 준 행과 우리가 쌓은 행과 뺀 행을 같이 센다. */
public record ObservationBatch(
    ObservationAttempt attempt,
    ObservationBatchOutcome outcome,
    int providerRows,
    int storedRows,
    int excludedRows,
    String normalizationVersion
) {

    /**
     * 지금 도는 정규화 규칙의 판. 규칙이 바뀌면 올린다.
     * 응답 원문을 따로 안 남겨서 관측이 사실상 원본이고, 이 값이 있어야
     * 나중에 어느 규칙으로 접힌 값인지 되짚는다.
     */
    public static final String CURRENT_NORMALIZATION_VERSION = "normalization-v1.0.0";

    public static ObservationBatch of(
        ObservationAttempt attempt,
        CollectedObservations collected
    ) {
        return new ObservationBatch(
            attempt,
            ObservationBatchOutcome.forProviderRows(collected.providerRows()),
            collected.providerRows(),
            collected.storableRows().size(),
            collected.excludedRows().size(),
            CURRENT_NORMALIZATION_VERSION);
    }
}
