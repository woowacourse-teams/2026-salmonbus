package com.gustler.backend.collector;

/**
 * 한 판의 결말.
 *
 * <p>지금은 성공 두 갈래뿐이다. 실패 갈래와 예약 사다리(예약만 하고 안 보냄 · 보냈는데 결과 모름)는
 * 호출 장부를 만드는 SAL-85 가 붙인다.
 */
public enum ObservationBatchOutcome {

    SUCCESS_ROWS,
    SUCCESS_EMPTY,
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
