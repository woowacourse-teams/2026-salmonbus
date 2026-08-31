package com.gustler.backend.collector;

/**
 * 자리를 잡으려고 해본 결과.
 *
 * <p>자리를 못 잡아도 묶음 행은 남는다. 그 판이 왜 비었는지를 나중에 알아야 해서다.
 * reserved 가 거짓이면 호출을 보내지 않는다.
 */
public record ObservationBatchReservation(
    long batchId,
    boolean reserved
) {
}
