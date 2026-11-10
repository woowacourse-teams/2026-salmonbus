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
    void 토큰이_16진수가_아니거나_길이가_틀리면_거절한다(String token) {
        assertThatThrownBy(() -> new DeviceToken(token)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 토큰은_1자부터_200자까지의_16진수를_받는다() {
        assertThat(new DeviceToken("A").value()).isEqualTo("a");
        assertThat(new DeviceToken("AB09ef".repeat(33) + "AB").value())
            .isEqualTo("ab09ef".repeat(33) + "ab");
        assertThatThrownBy(() -> new DeviceToken("a".repeat(201))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 소문자_변환은_기본_로케일과_상관없다() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(new DeviceToken("ABCDEF")).isEqualTo(new DeviceToken("abcdef"));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void 진단_문자열에_토큰을_싣지_않는다() {
        assertThat(new DeviceToken("abcdef").toString()).doesNotContain("abcdef");
        assertThatThrownBy(() -> new DeviceToken("private-token"))
            .hasMessageNotContaining("private-token");
    }
}
