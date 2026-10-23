package com.gustler.backend.api.favorite.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(requiredProperties = "groupIds")
public record ReorderFavoriteGroupsRequest(
    @Schema(description = "현재 내 그룹 ID를 원하는 순서로 빠짐없이 담은 목록")
    List<UUID> groupIds
) {
}
