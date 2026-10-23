package com.gustler.backend.api.favorite.domain;

import java.util.Objects;

/** 즐겨찾기 그룹 안에서 순서를 갖는 즐겨찾기 한 건이다. */
public record Favorite(
    FavoriteId id,
    FavoriteTarget target
) {
    public Favorite {
        Objects.requireNonNull(id, "즐겨찾기 ID는 null일 수 없습니다.");
        Objects.requireNonNull(target, "즐겨찾기 대상은 null일 수 없습니다.");
    }
}
