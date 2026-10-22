package com.gustler.backend.api.http.login;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ReturnToTest {
    @ParameterizedTest
    @ValueSource(strings = {
        "/",
        "/routes/123456789",
        "/routes/123456789?direction=UP",
        "/routes/123456789?direction=DOWN&stopId=224000586",
        "/routes/123456789?stopId=ab-1_2&direction=UP",
    })
    void 허용한_경로와_쿼리는_그대로_돌려준다(String candidate) {
        // when
        String actual = ReturnTo.sanitize(candidate);

        // then
        assertThat(actual).isEqualTo(candidate);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
        "",
        "//evil.com",
        "///evil.com",
        "https://evil.com",
        "http://evil.com/routes/123456789",
        "/\\evil.com",
        "\\\\evil.com",
        "javascript:alert(1)",
        "/routes/12345678",
        "/routes/1234567890",
        "/routes/123456789/extra",
        "/routes/123456789?direction=LEFT",
        "/routes/123456789?direction=UP&direction=DOWN",
        "/routes/123456789?next=https://evil.com",
        "/routes/123456789?stopId=%2F%2Fevil.com",
        "/routes/123456789?stopId=a b",
        "/routes/123456789?",
        "/routes/123456789#fragment",
        "/routes/123456789\r\nSet-Cookie: a=b",
        "/other",
        "/?direction=UP",
    })
    void 허용하지_않은_값은_첫_화면으로_바꾼다(String candidate) {
        // when
        String actual = ReturnTo.sanitize(candidate);

        // then
        assertThat(actual).isEqualTo("/");
    }
}
