package com.gustler.backend.api.favorite.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = "name")
public record RenameFavoriteGroupRequest(
    @Schema(description = "앞뒤 공백을 제외한 1자 이상 10자 이하의 새 그룹 이름", example = "평일 출근")
    String name
) {
}
