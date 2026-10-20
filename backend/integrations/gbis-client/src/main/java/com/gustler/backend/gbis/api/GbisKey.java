package com.gustler.backend.gbis.api;

/**
 * 호출 예산에서 사용하는 별칭과 인증값이다.
 * 문자열 표현에는 별칭만 공개한다.
 */
public record GbisKey(
    String alias,
    String serviceKey
) {
    public static final String PRIMARY = "a";

    @Override
    public String toString() {
        return "GbisKey[alias=%s, serviceKey=***]".formatted(alias);
    }
}
