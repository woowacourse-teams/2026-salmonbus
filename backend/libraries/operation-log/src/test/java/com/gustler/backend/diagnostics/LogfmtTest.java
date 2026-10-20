package com.gustler.backend.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LogfmtTest {
    @Test
    void 빈값과_특수문자를_한줄의_따옴표_문자열로_표현한다() {
        assertThat(Logfmt.quote(null)).isEqualTo("\"\"");
        assertThat(Logfmt.quote("")).isEqualTo("\"\"");
        assertThat(Logfmt.quote("3330 판교\"역\"\\분기\n다음\r\t\u0001"))
            .isEqualTo("\"3330 판교\\\"역\\\"\\\\분기\\n다음\\r\\t\\u0001\"")
            .doesNotContain("\n", "\r", "\t");
    }
}
