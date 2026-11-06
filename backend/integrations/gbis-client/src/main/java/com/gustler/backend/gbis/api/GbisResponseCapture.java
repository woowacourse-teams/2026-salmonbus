package com.gustler.backend.gbis.api;

import java.util.Objects;

/** Screened transport bytes, before decoding. Endpoint schema acceptance is still required before archiving. */
public record GbisResponseCapture(Disposition disposition, byte[] body) {
    public enum Disposition { AVAILABLE, SENSITIVE_RESPONSE, NOT_RETAINED }
    public static final GbisResponseCapture NONE = new GbisResponseCapture(Disposition.NOT_RETAINED, null);
    public GbisResponseCapture {
        Objects.requireNonNull(disposition);
        if ((disposition == Disposition.AVAILABLE) != (body != null && body.length > 0))
            throw new IllegalArgumentException("Invalid transport capture");
        if (disposition != Disposition.AVAILABLE && body != null)
            throw new IllegalArgumentException("Withheld capture cannot retain bytes");
        body = body == null ? null : body.clone();
    }
    @Override public byte[] body() { return body == null ? null : body.clone(); }
    @Override public String toString() {
        return "GbisResponseCapture[disposition=" + disposition + ", byteCount=" + (body == null ? 0 : body.length) + "]";
    }
}
