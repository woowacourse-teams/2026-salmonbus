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
        final Integer code
    ) {
        this.code = code;
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
