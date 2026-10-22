package com.gustler.backend.api.account.token;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class BearerTokensTest {
    @Test
    void 헤더가_있으면_헤더를_쿠키보다_먼저_본다() {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer from-header");
        request.setCookies(new Cookie(AuthCookies.ACCESS, "from-cookie"));

        // when & then
        assertSoftly(softly -> softly.assertThat(BearerTokens.access(request)).contains("from-header"));
    }

    @Test
    void 헤더가_없으면_쿠키를_본다() {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AuthCookies.ACCESS, "from-cookie"));

        // when & then
        assertSoftly(softly -> softly.assertThat(BearerTokens.access(request)).contains("from-cookie"));
    }

    @Test
    void 비어_있거나_Bearer가_아닌_헤더는_토큰으로_보지_않는다() {
        // given
        MockHttpServletRequest empty = new MockHttpServletRequest();
        empty.addHeader("Authorization", "Bearer   ");
        MockHttpServletRequest basic = new MockHttpServletRequest();
        basic.addHeader("Authorization", "Basic abc");

        // when & then
        assertSoftly(softly -> {
            softly.assertThat(BearerTokens.access(empty)).isEmpty();
            softly.assertThat(BearerTokens.access(basic)).isEmpty();
        });
    }

    @Test
    void 인증_쿠키만으로_인증하는_요청만_CSRF_대상이다() {
        // given
        MockHttpServletRequest none = new MockHttpServletRequest();
        MockHttpServletRequest cookieOnly = new MockHttpServletRequest();
        cookieOnly.setCookies(new Cookie(AuthCookies.REFRESH, "rt"));
        MockHttpServletRequest headerAndCookie = new MockHttpServletRequest();
        headerAndCookie.addHeader("Authorization", "Bearer x");
        headerAndCookie.setCookies(new Cookie(AuthCookies.ACCESS, "at"));

        // when & then
        assertSoftly(softly -> {
            softly.assertThat(BearerTokens.usesAmbientCredentials(none)).isFalse();
            softly.assertThat(BearerTokens.usesAmbientCredentials(cookieOnly)).isTrue();
            softly.assertThat(BearerTokens.usesAmbientCredentials(headerAndCookie)).isFalse();
        });
    }
}
