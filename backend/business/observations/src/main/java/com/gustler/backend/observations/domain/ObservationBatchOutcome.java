package com.gustler.backend.observations.domain;

/**
 * 수집 배치의 진행 상태와 결과.
 *
 * <p>성공과 실패 외에 예약과 전송 상태도 구분한다. 하루 한도는 10,000회이고 두 노선의 실측 호출량은
 * 8,194회이므로 전송 전에 호출 횟수를 예약한다. 예약 후 미전송과 전송 후 결과 미확인을 구분해야
 * 재시도로 호출 한도가 중복 사용됐는지 알 수 있다.
 *
 * <p>SUCCESS_ROWS 와 SUCCESS_EMPTY 의 철자는 바꾸지 않는다.
 * V1__collector.sql 93행의 부분 인덱스가 이 두 값을 그대로 사용한다.
 */
public enum ObservationBatchOutcome {
    /** 호출 횟수를 예약했다. 아직 전송하지 않았다. */
    RESERVED,
    /** 전송했고 응답을 기다린다. */
    DISPATCHING,
    /** 오늘 한도가 부족해 예약하지 못했다. 전송하지 않았고 한도도 사용하지 않았다. */
    NOT_RESERVED,
    /** 예약 후 전송 전에 중단했다. 예약한 호출 횟수는 이미 한도에서 차감했다. */
    ABANDONED_BEFORE_SEND,
    /** 전송했지만 응답이 오지 않아 상류가 호출 횟수를 집계했는지 알 수 없다. */
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
    public static ObservationBatchOutcome forProviderRows(
        final int providerRows
    ) {
        if (providerRows > 0) {
            return SUCCESS_ROWS;
        }
        return SUCCESS_EMPTY;
    }
}
