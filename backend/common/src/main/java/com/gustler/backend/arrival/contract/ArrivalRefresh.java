package com.gustler.backend.arrival.contract;

import java.time.Instant;

/** 내부 갱신 요청의 빠른 응답. 작업의 완료를 기다리는 HTTP 구독을 만들지 않는다. */
public record ArrivalRefresh(Status status, String reason, Instant suggestedRefreshAt) {
    public enum Status { ACCEPTED, IN_PROGRESS, DEFERRED, IDLE, UNAVAILABLE }
    public record Request(String stationId) { }
}
