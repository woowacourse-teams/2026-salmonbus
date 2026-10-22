package com.gustler.backend.api.http.login;

import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.Base64;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 응답의 {@code Set-Cookie} 한 줄과 JWT 본문을 꺼내 보는 테스트 도구. */
final class CookieHeaders {
    private CookieHeaders() {
    }

    /** 이름이 맞는 {@code Set-Cookie} 전체 줄. 없으면 빈 문자열이다. */
    static String named(HttpResponse<?> response, String name) {
        return response.headers().allValues("Set-Cookie").stream()
            .filter(header -> header.startsWith(name + "="))
            .findFirst()
            .orElse("");
    }

    static String valueOf(String setCookie) {
        return setCookie.substring(setCookie.indexOf('=') + 1).split(";", 2)[0];
    }

    static long maxAgeOf(String setCookie) {
        return Arrays.stream(setCookie.split(";"))
            .map(String::strip)
            .filter(attribute -> attribute.regionMatches(true, 0, "Max-Age=", 0, "Max-Age=".length()))
            .mapToLong(attribute -> Long.parseLong(attribute.substring("Max-Age=".length())))
            .findFirst()
            .orElse(-1);
    }

    static JsonNode jwtPayload(String jwt, ObjectMapper objectMapper) {
        String payload = jwt.split("\\.")[1];
        return objectMapper.readTree(new String(Base64.getUrlDecoder().decode(payload)));
    }
}
