package com.gustler.backend.observations.domain;

/**
 * 수집 배치의 실패 사유.
 *
 * <p>수집 결과만으로 사유를 알 수 있으면 비워 둔다. 응답 미수신, 응답 해석 실패,
 * 전송 전 중단은 결과 상태로 구분되므로 별도의 실패 사유가 필요하지 않다.
 */
public enum ObservationBatchFailureCode {

    /** 오늘 예약할 호출 한도가 남지 않아 호출을 보내지 않았다. */
    LOCAL_QUOTA_EXHAUSTED,
    /** 상류가 하루 한도 초과를 응답했다. 내부 호출 집계와 상류의 집계가 다르다. */
    DAILY_QUOTA_EXCEEDED,
    /** 상류가 초당 한도를 넘겼다고 답했다. */
    PER_SECOND_QUOTA_EXCEEDED,
    /** 상류가 그 밖의 오류로 답했다. */
    UPSTREAM_ERROR,
    ;
}
