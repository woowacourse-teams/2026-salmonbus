package com.gustler.backend.api.favorite.dto;

import com.gustler.backend.api.favorite.domain.FavoriteDirection;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(requiredProperties = {"routeId", "stopId", "direction", "favoriteGroupIds"})
public record SaveFavoriteSelectionRequest(
    @Schema(description = "9자리 GBIS 노선 ID", example = "234001739")
    String routeId,
    @Schema(description = "9자리 GBIS 정류장 ID", example = "214002018")
    String stopId,
    @Schema(description = "클라이언트가 선택한 정류장 방문 방향", example = "UP")
    FavoriteDirection direction,
    @Schema(description = "이 노선 정류장 항목을 저장할 내 즐겨찾기 그룹 ID 전체")
    List<UUID> favoriteGroupIds
) {
    public SaveFavoriteSelectionRequest {
        if (favoriteGroupIds != null) {
            favoriteGroupIds = List.copyOf(favoriteGroupIds);
        }
    }
}
