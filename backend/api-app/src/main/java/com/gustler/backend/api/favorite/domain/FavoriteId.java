package com.gustler.backend.api.favorite.domain;

import java.util.Objects;
import java.util.UUID;

/** 외부 API에서 그룹에 속한 즐겨찾기 한 건을 식별하는 값이다. */
public record FavoriteId(
    UUID value
) {
    public FavoriteId {
        Objects.requireNonNull(value, "즐겨찾기 ID는 null일 수 없습니다.");
    }
}
