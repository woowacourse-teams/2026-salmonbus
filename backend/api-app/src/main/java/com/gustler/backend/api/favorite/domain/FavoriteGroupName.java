package com.gustler.backend.api.favorite.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/** 앞뒤 공백을 제거한 뒤 사용자가 보는 문자 수로 제한하는 즐겨찾기 그룹 이름이다. */
public record FavoriteGroupName(
    String value
) {
    public static final int MAX_GRAPHEMES = 10;
    private static final Pattern GRAPHEME = Pattern.compile("\\X");

    public FavoriteGroupName {
        Objects.requireNonNull(value, "즐겨찾기 그룹 이름은 null일 수 없습니다.");
        value = value.strip();
        final long graphemes = GRAPHEME.matcher(value).results().count();
        if (graphemes == 0 || graphemes > MAX_GRAPHEMES) {
            throw new IllegalArgumentException("즐겨찾기 그룹 이름은 1자 이상 10자 이하여야 합니다.");
        }
    }
}
