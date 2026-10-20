package com.gustler.backend.gbis.api;

import java.util.Arrays;
import java.util.Objects;

/**
 * 공공데이터포털의 한도·키 거부 코드를 구분한다.
 * 알려지지 않은 코드와 null은 OTHER다.
 */
public enum PortalReasonCode {
    DAILY_QUOTA_EXCEEDED("22"),
    PER_SECOND_QUOTA_EXCEEDED("23"),
    UNREGISTERED_KEY("30"),
    EXPIRED_KEY("31"),
    OTHER(null);

    private final String code;

    PortalReasonCode(
        String code
    ) {
        this.code = code;
    }

    public static PortalReasonCode from(
        String reasonCode
    ) {
        return Arrays.stream(values())
            .filter(candidate -> Objects.equals(candidate.code, reasonCode))
            .findFirst()
            .orElse(OTHER);
    }

    public String code() {
        return code;
    }
}
