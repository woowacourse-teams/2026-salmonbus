package com.gustler.backend.api.push.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PushEnvironmentTest {
    @ParameterizedTest
    @EnumSource(PushEnvironment.class)
    void acceptsExactEnvironment(PushEnvironment environment) {
        assertThat(PushEnvironment.from(environment.name())).isEqualTo(environment);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"sandbox", "production", " SANDBOX", "PRODUCTION ", "OTHER"})
    void rejectsOtherEnvironment(String value) {
        assertThatThrownBy(() -> PushEnvironment.from(value)).isInstanceOf(IllegalArgumentException.class);
    }
}
