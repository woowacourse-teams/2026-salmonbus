package com.gustler.backend.api.board;

public class NoRecentObservationException extends RuntimeException {

    public NoRecentObservationException() {
        super("예보 기준으로 사용할 최근 차량 관측이 없습니다.");
    }
}
