package com.gustler.backend.api.push.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class DeviceTokenTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "ab cd", " ab", "ab ", "xyz", "ab\n", "１２", "0x12"})
    void rejectsInvalidTokens(String token) {
        assertThatThrownBy(() -> new DeviceToken(token)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsOneToTwoHundredHexCharacters() {
        assertThat(new DeviceToken("A").value()).isEqualTo("a");
        assertThat(new DeviceToken("AB09ef".repeat(33) + "AB").value())
            .isEqualTo("ab09ef".repeat(33) + "ab");
        assertThatThrownBy(() -> new DeviceToken("a".repeat(201))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalizationDoesNotDependOnDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(new DeviceToken("ABCDEF")).isEqualTo(new DeviceToken("abcdef"));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void tokenDoesNotAppearInDiagnosticText() {
        assertThat(new DeviceToken("abcdef").toString()).doesNotContain("abcdef");
        assertThatThrownBy(() -> new DeviceToken("private-token"))
            .hasMessageNotContaining("private-token");
    }
}
