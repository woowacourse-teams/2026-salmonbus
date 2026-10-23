package com.gustler.backend.api.favorite.domain;

import java.util.Objects;
import java.util.UUID;

/** 외부 API에서 즐겨찾기 그룹을 식별하는 값이다. */
public record FavoriteGroupId(
    UUID value
) {
    public FavoriteGroupId {
        Objects.requireNonNull(value, "즐겨찾기 그룹 ID는 null일 수 없습니다.");
    }
}
