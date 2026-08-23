package com.gustler.backend.api.board;

public class NoRecentObservationException extends RuntimeException {

    public NoRecentObservationException() {
        super("no vehicle observation recent enough to anchor a forecast");
    }
}
