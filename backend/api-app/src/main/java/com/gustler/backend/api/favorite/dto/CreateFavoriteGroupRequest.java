package com.gustler.backend.api.favorite.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = "name")
public record CreateFavoriteGroupRequest(
    @Schema(description = "앞뒤 공백을 제외한 1자 이상 10자 이하의 그룹 이름", example = "출근")
    String name,
    @Schema(description = "그룹을 만들면서 함께 저장할 첫 노선 정류장 항목. 없으면 생략")
    FavoriteTargetRequest initialFavorite
) {
}
