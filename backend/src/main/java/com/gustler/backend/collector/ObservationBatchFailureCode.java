package com.gustler.backend.collector;

/**
 * 판이 실패로 끝난 이유.
 *
 * <p>결말이 이미 다 말하는 경우에는 비운다. 응답이 안 온 판과 응답을 못 읽은 판과
 * 보내기 전에 그만둔 판이 그렇다. 결말 하나로 사건이 특정돼서 더 좁힐 것이 없다.
 */
public enum ObservationBatchFailureCode {

    /** 우리 장부에 오늘 자리가 없다. 호출을 보내지 않았다. */
    LOCAL_QUOTA_EXHAUSTED,
    /** 상류가 하루 한도를 넘겼다고 답했다. 우리 장부가 상류와 어긋나 있다는 뜻이다. */
    DAILY_QUOTA_EXCEEDED,
    /** 상류가 초당 한도를 넘겼다고 답했다. */
    PER_SECOND_QUOTA_EXCEEDED,
    /** 상류가 그 밖의 오류로 답했다. */
    UPSTREAM_ERROR,
    ;
}
