package com.gustler.backend.api.favorite.dto;

import com.gustler.backend.api.favorite.application.FavoriteOverview.Group;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(requiredProperties = {"id", "name", "favorites"})
public record FavoriteOverviewGroupResponse(
    UUID id,
    String name,
    @Schema(description = "그룹 안에서 회원이 정한 순서의 노선 정류장 항목")
    List<FavoriteItemResponse> favorites
) {
    public FavoriteOverviewGroupResponse {
        favorites = List.copyOf(favorites);
    }

    static FavoriteOverviewGroupResponse from(Group group) {
        return new FavoriteOverviewGroupResponse(group.id(), group.name(), group.favorites().stream()
            .map(FavoriteItemResponse::from)
            .toList());
    }
}
