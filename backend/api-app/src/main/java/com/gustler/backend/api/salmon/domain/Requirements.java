package com.gustler.backend.api.salmon.domain;

import java.util.Optional;

final class Requirements {
    private Requirements() {
    }

    // 음수 우회량은 막지 않는다. 채택된 D 정의가 없어 음수 허용 여부는 SAL-389에서 D 정의와 함께 정한다.
    static void requireDetour(Optional<Double> detour) {
        detour.ifPresent(value -> requireFinite(value, "detour"));
    }

    static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    static void requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Identifier must not be blank");
        }
    }
}
