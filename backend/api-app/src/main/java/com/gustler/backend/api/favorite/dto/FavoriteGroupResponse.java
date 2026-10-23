package com.gustler.backend.api.favorite.dto;

import com.gustler.backend.api.favorite.domain.FavoriteGroup;
import java.util.List;
import java.util.UUID;

public record FavoriteGroupResponse(UUID id, String name, List<FavoriteResponse> favorites) {
    public FavoriteGroupResponse {
        favorites = List.copyOf(favorites);
    }

    public static FavoriteGroupResponse from(FavoriteGroup group) {
        return new FavoriteGroupResponse(group.id().value(), group.name().value(),
            group.favorites().stream().map(FavoriteResponse::from).toList());
    }
}
