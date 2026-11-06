package com.gustler.backend.gbis.api;

/** No request URL or credential is part of this metadata. Capture bytes must pass endpoint validation before retention. */
public record GbisHttpResult(GbisRawResponse response, int httpStatus, String retryAfter,
        GbisResponseCapture capture) {
    public GbisHttpResult(GbisRawResponse response, int httpStatus, String retryAfter) {
        this(response, httpStatus, retryAfter, GbisResponseCapture.NONE);
    }
    public GbisHttpResult {
        if (capture == null) capture = GbisResponseCapture.NONE;
    }
    @Override public String toString() {
        return "GbisHttpResult[httpStatus=" + httpStatus + ", responseType="
            + (response == null ? "NONE" : response.getClass().getSimpleName())
            + ", capture=" + capture + "]";
    }
}
