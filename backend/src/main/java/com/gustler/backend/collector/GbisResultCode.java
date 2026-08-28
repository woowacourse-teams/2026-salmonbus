package com.gustler.backend.collector;

import java.util.Arrays;
import java.util.Objects;

public enum GbisResultCode {

    SUCCESS(0),
    SYSTEM_FAILURE(1),
    PARAMETER_MISSING(2),
    NO_VEHICLES(4),
    OTHER(null);

    private final Integer code;

    GbisResultCode(
        Integer code
    ) {
        this.code = code;
    }

    /** OTHER 는 무슨 코드였는지 특정하지 못해 비어 있다. */
    public Integer code() {
        return code;
    }

    public static GbisResultCode from(
        final int resultCode
    ) {
        return Arrays.stream(values())
            .filter(candidate -> Objects.equals(candidate.code, resultCode))
            .findFirst()
            .orElse(OTHER);
    }
}
