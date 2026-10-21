package com.gustler.backend.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LogfmtTest {
    @Test
    void 빈값과_특수문자를_한줄의_따옴표_문자열로_표현한다() {
        // when
        String quotedValue1 = Logfmt.quote(null);

        // then
        assertThat(quotedValue1).isEqualTo("\"\"");

        // when
        String quotedValue2 = Logfmt.quote("");

        // then
        assertThat(quotedValue2).isEqualTo("\"\"");

        // when
        String quotedValue3 = Logfmt.quote("3330 판교\"역\"\\분기\n다음\r\t\u0001");

        // then
        assertThat(quotedValue3)
            .isEqualTo("\"3330 판교\\\"역\\\"\\\\분기\\n다음\\r\\t\\u0001\"")
            .doesNotContain("\n", "\r", "\t");
    }
}
