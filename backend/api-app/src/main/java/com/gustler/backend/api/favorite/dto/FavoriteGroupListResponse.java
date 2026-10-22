package com.gustler.backend.api.favorite.dto;

import com.gustler.backend.api.favorite.domain.MemberFavoriteGroups;
import java.util.List;

public record FavoriteGroupListResponse(List<FavoriteGroupResponse> groups) {
    public static FavoriteGroupListResponse from(MemberFavoriteGroups groups) {
        return new FavoriteGroupListResponse(groups.values().stream().map(FavoriteGroupResponse::from).toList());
    }
}
