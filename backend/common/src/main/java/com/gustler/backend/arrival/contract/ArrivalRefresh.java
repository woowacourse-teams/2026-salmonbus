package com.gustler.backend.arrival.contract;

import com.gustler.backend.arrival.contract.ArrivalRefresh.Status;
import java.time.Instant;

/** 내부 갱신 접수 결과. 별도 헤더를 보낸 요청만 제한 시간 동안 수집 완료를 기다린다. */
public record ArrivalRefresh(Status status, String reason, Instant suggestedRefreshAt) {
    public static final String WAIT_HEADER = "X-Arrival-Wait-Millis";
    public static final int MAX_WAIT_MILLIS = 1000;

    public enum Status {
        ACCEPTED, IN_PROGRESS, DEFERRED, IDLE, UNAVAILABLE
    }
    public record Request(String stationId) {
    }
}
