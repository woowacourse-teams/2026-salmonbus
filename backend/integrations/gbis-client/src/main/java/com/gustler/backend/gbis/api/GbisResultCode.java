package com.gustler.backend.gbis.api;

import java.util.Arrays;
import java.util.Objects;

/**
 * 위치·노선 응답 헤더의 결과 코드를 해석한다.
 * 알 수 없는 값은 OTHER로 보존한다.
 */
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

    public static GbisResultCode from(
        final int resultCode
    ) {
        return Arrays.stream(values())
            .filter(candidate -> Objects.equals(candidate.code, resultCode))
            .findFirst()
            .orElse(OTHER);
    }

    /** OTHER 는 원래 코드를 특정할 수 없어 비어 있다. */
    public Integer code() {
        return code;
    }
}
