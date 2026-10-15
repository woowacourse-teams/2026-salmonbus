package com.gustler.devtools;

final class Checks {
    private Checks() {}

    static void require(final boolean condition, String code) {
        if (!condition) {
            throw new IllegalArgumentException(code);
        }
    }
}
