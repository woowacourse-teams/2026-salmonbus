package com.gustler.backend.api.board;

import com.gustler.backend.api.http.ApiException;
import com.gustler.backend.api.http.ErrorCode;
import java.time.Duration;

public class NoRecentObservationException extends ApiException {

    public NoRecentObservationException() {
        super(ErrorCode.NO_RECENT_OBSERVATION);
    }

    public NoRecentObservationException(
        final Duration retryAfter
    ) {
        super(ErrorCode.NO_RECENT_OBSERVATION, retryAfter);
    }
}
