package com.gustler.backend.api.favorite.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(requiredProperties = "favoriteIds")
public record ReorderFavoritesRequest(
    @Schema(description = "그룹의 현재 노선 정류장 항목 ID를 원하는 순서로 빠짐없이 담은 목록")
    List<UUID> favoriteIds
) {
}
