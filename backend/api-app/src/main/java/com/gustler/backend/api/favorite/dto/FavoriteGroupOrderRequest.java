package com.gustler.backend.api.favorite.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(requiredProperties = {"groupId", "favoriteIds"})
public record FavoriteGroupOrderRequest(
    @Schema(description = "내 즐겨찾기 그룹 ID")
    UUID groupId,
    @Schema(
        description = "그룹의 현재 노선 정류장 항목 ID를 원하는 순서로 빠짐없이 담은 목록"
    )
    List<UUID> favoriteIds
) {
    public FavoriteGroupOrderRequest {
        if (favoriteIds != null) {
            favoriteIds = List.copyOf(favoriteIds);
        }
    }
}
