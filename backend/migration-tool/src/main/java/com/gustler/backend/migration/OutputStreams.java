package com.gustler.backend.migration;

import java.io.OutputStream;

final class OutputStreams {

    private static final OutputStream DISCARDING = OutputStream.nullOutputStream();

    private OutputStreams() {
    }

    static OutputStream discarding() {
        return DISCARDING;
    }
}
