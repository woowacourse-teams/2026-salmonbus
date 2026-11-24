package com.gustler.backend.observations.domain;

/**
 * 수집 배치의 진행 상태와 결과.
 *
 * <p>호출 전 한도 예약과 DISPATCHING 준비를 함께 저장한다. 결과를 확정하지 못한 채 중단되면
 * 다음 회차에서 기록만 종료하고 같은 관측을 재조회하지 않는다. RESERVED는 기존 이력 호환을 위해 유지한다.
 *
 * <p>SUCCESS_ROWS 와 SUCCESS_EMPTY 의 철자는 바꾸지 않는다.
 * V1__collector.sql 93행의 부분 인덱스가 이 두 값을 그대로 사용한다.
 */
public enum CollectionBatchStatus {

    /** 기존 이력의 예약 상태. 새 수집에서는 별도로 기록하지 않는다. */
    RESERVED,
    /** 한도를 예약하고 호출을 준비했다. 응답 결과를 아직 확정하지 않았다. */
    DISPATCHING,
    /** 오늘 한도가 부족해 예약하지 못했다. 전송하지 않았고 한도도 사용하지 않았다. */
    NOT_RESERVED,
    /** 예약 후 전송 전에 중단했다. 예약한 호출 횟수는 이미 한도에서 차감했다. */
    ABANDONED_BEFORE_SEND,
    /** 전송 후 수집 결과를 DB에 확정하지 못했다. 응답 미수신·저장 실패 등은 원인 로그로 구분한다. */
    UNKNOWN_AFTER_DISPATCH,
    SUCCESS_ROWS,
    SUCCESS_EMPTY,
    /** 상류가 오류로 응답했다. 구체적인 원인은 실패 사유에 기록한다. */
    FAILED_UPSTREAM,
    /** 응답을 받았지만 읽지 못했다. */
    FAILED_UNREADABLE,
    ;

    /**
     * 상류가 준 행 수로 정하며 우리가 저장한 행 수로 정하지 않는다.
     * "상류가 차가 없다고 했다"와 "차는 있었는데 우리가 다 뺐다"는 다른 사실이고,
     * 관측 간격 90초 판정에서 의미가 달라진다.
     */
    public static CollectionBatchStatus forProviderRows(final int providerRows) {
        if (providerRows > 0) {
            return SUCCESS_ROWS;
        }
        return SUCCESS_EMPTY;
    }
}
