package com.gustler.backend.api.arrival;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;

public record ArrivalQuery(String stationId, String routeId, Long referenceVersionId, Integer stopSequence) {
    public ArrivalQuery {
        if (stationId == null || !stationId.matches("[0-9]{9}")
            || (routeId != null && !routeId.matches("[0-9]{9}"))
            || ((referenceVersionId == null) != (stopSequence == null))
            || (referenceVersionId != null && (routeId == null || referenceVersionId < 1 || stopSequence < 1))) {
            throw new Failure(ErrorCode.INVALID_REQUEST);
        }
    }

    public static final class Failure extends ApiException {
        public Failure(ErrorCode code) { super(code); }
    }
}
