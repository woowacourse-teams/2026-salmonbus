package com.gustler.backend.gbis.api;

import java.util.Arrays;
import java.util.Objects;

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

    public String code() {
        return code;
    }

    public static PortalReasonCode from(
        String reasonCode
    ) {
        return Arrays.stream(values())
            .filter(candidate -> Objects.equals(candidate.code, reasonCode))
            .findFirst()
            .orElse(OTHER);
    }
}
