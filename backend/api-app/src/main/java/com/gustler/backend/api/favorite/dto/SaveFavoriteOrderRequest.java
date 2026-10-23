package com.gustler.backend.api.favorite.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(requiredProperties = "groups")
public record SaveFavoriteOrderRequest(
    @Schema(
        description = "현재 내 그룹과 각 그룹의 노선 정류장 항목을 "
            + "원하는 순서로 빠짐없이 담은 목록"
    )
    List<FavoriteGroupOrderRequest> groups
) {
    public SaveFavoriteOrderRequest {
        if (groups != null) {
            groups = List.copyOf(groups);
        }
    }
}
