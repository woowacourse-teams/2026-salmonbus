package com.gustler.backend.api.favorite.domain;

import java.util.List;
import java.util.Objects;

/** 즐겨찾기 그룹 한 개와 그 안의 즐겨찾기를 함께 정렬하기 위한 전체 순서다. */
public record FavoriteGroupOrder(
    FavoriteGroupId groupId,
    List<FavoriteId> favoriteIds
) {
    public FavoriteGroupOrder {
        Objects.requireNonNull(groupId, "즐겨찾기 그룹 ID는 null일 수 없습니다.");
        List<FavoriteId> requiredFavoriteIds = Objects.requireNonNull(
            favoriteIds,
            "즐겨찾기 순서는 null일 수 없습니다."
        );
        favoriteIds = List.copyOf(requiredFavoriteIds);
    }
}
