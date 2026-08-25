package com.gustler.backend.api.board;

import com.gustler.backend.api.http.ApiException;
import com.gustler.backend.api.http.ErrorCode;

public class NoRecentObservationException extends ApiException {

    public NoRecentObservationException() {
        super(ErrorCode.NO_RECENT_OBSERVATION);
    }
}
