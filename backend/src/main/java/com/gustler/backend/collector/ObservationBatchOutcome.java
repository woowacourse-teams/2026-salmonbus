package com.gustler.backend.collector;

/**
 * 한 판이 어디까지 갔나.
 *
 * <p>성공 · 실패만으로는 모자란다. 하루 한도가 10,000 인데 두 노선 실측이 8,194 회라
 * 보내기 전에 장부에서 자리를 먼저 잡는다. 그래서 "자리를 잡았는데 아직 안 보냈다"와
 * "보냈는데 결과를 모른다"가 각각 다른 사건이고, 뭉치면 재시도가 한도를 두 번 쓰고도 모른다.
 *
 * <p>SUCCESS_ROWS 와 SUCCESS_EMPTY 의 철자는 바꾸지 않는다.
 * V1__collector.sql 93행의 부분 인덱스가 이 두 글자를 그대로 담고 있다.
 */
public enum ObservationBatchOutcome {

    /** 장부에 자리를 잡았다. 아직 안 보냈다. */
    RESERVED,
    /** 보냈다. 응답을 기다린다. */
    DISPATCHING,
    /** 오늘 자리가 남지 않아 못 잡았다. 보내지 않았고 한도도 안 썼다. */
    NOT_RESERVED,
    /** 자리를 잡고 보내기 전에 그만뒀다. 한도는 이미 썼다. */
    ABANDONED_BEFORE_SEND,
    /** 보냈는데 응답이 안 왔다. 상류가 셌는지 모른다. */
    UNKNOWN_AFTER_DISPATCH,
    SUCCESS_ROWS,
    SUCCESS_EMPTY,
    /** 상류가 오류로 답했다. 왜인지는 실패 사유가 말한다. */
    FAILED_UPSTREAM,
    /** 응답이 왔는데 읽지 못했다. */
    FAILED_UNREADABLE,
    ;

    /**
     * 상류가 준 행 수로 정한다. 우리가 쌓은 행 수가 아니다.
     * "상류가 차가 없다고 했다"와 "차는 있었는데 우리가 다 뺐다"는 다른 사실이고,
     * 관측 간격 90초 판정에서 뜻이 갈린다.
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
