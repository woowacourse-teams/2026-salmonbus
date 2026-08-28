package com.gustler.backend.collector;

import java.time.OffsetDateTime;

/** 묶음 행을 열고 사다리를 한 칸씩 올린다. */
public interface ObservationRepository {

    /** 자리를 잡은 판을 연다. 같은 계획의 판이 이미 있으면 시도 횟수를 올리고 처음 상태로 되돌린다. */
    long openReserved(
        ObservationAttempt attempt
    );

    /** 자리를 못 잡아 보내지 않은 판을 연다. 여기서 끝난다. */
    long openNotReserved(
        ObservationAttempt attempt
    );

    /** 보내기 직전. 이 시각이 들어가야 "예약만 하고 안 보냈다"와 갈린다. */
    void markDispatching(
        long batchId,
        OffsetDateTime requestedAt
    );

    /** 자리를 잡고 보내기 전에 그만뒀다. 한도는 이미 썼으므로 되돌리지 않는다. */
    void abandonBeforeSend(
        long batchId
    );

    /** 쌓을 관측 없이 판을 닫는다. 상류가 오류로 답했거나 응답이 아예 안 온 판이다. */
    void concludeWithoutRows(
        long batchId,
        ObservationBatchConclusion conclusion,
        OffsetDateTime responseReceivedAt
    );

    /** 관측을 쌓으면서 판을 닫는다. 상류가 정상으로 답한 판이다. */
    void concludeWithRows(
        long batchId,
        ObservationBatchConclusion conclusion,
        CollectedObservations collected,
        OffsetDateTime responseReceivedAt
    );
}
