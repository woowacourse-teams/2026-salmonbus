package com.gustler.backend.api.favorite.dto;

import com.gustler.backend.api.favorite.application.FavoriteOverview;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(requiredProperties = "groups")
public record FavoriteOverviewResponse(
    @Schema(description = "회원이 정한 순서의 즐겨찾기 그룹")
    List<FavoriteOverviewGroupResponse> groups
) {
    public FavoriteOverviewResponse {
        groups = List.copyOf(groups);
    }

    public static FavoriteOverviewResponse from(FavoriteOverview overview) {
        return new FavoriteOverviewResponse(overview.groups().stream()
            .map(FavoriteOverviewGroupResponse::from)
            .toList());
    }
}
