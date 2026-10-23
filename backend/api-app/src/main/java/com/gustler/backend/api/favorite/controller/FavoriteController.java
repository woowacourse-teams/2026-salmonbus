package com.gustler.backend.api.favorite.controller;

import com.gustler.backend.api.account.MemberPrincipal;
import com.gustler.backend.api.docs.ApiDocsTags;
import com.gustler.backend.api.error.ErrorResponse;
import com.gustler.backend.api.favorite.InvalidFavoriteRequestException;
import com.gustler.backend.api.favorite.application.FavoriteGroupService;
import com.gustler.backend.api.favorite.application.FavoriteSelectionService;
import com.gustler.backend.api.favorite.dto.ReorderFavoritesRequest;
import com.gustler.backend.api.favorite.dto.SaveFavoriteSelectionRequest;
import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = ApiDocsTags.FAVORITES)
@RequestMapping("/api/me")
@ConditionalOnProperty(name = "auth.enabled", havingValue = "true")
public class FavoriteController {
    private final FavoriteGroupService groups;
    private final FavoriteSelectionService selections;

    public FavoriteController(FavoriteGroupService groups, FavoriteSelectionService selections) {
        this.groups = groups;
        this.selections = selections;
    }

    @PutMapping("/favorite-selections")
    @Operation(operationId = "saveMyFavoriteSelection", summary = "노선 정류장 항목의 그룹 선택 저장",
        description = "이 항목을 저장할 그룹 ID 전체를 보냅니다. 이미 같은 선택이면 변경하지 않습니다. "
            + "한 그룹이라도 최대 2개를 넘으면 어떤 그룹도 변경하지 않습니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "선택 상태 저장 완료"),
        @ApiResponse(responseCode = "400", description = "노선, 정류장, 방향 또는 그룹 목록이 올바르지 않음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "내 즐겨찾기 그룹을 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "그룹 최대 개수 초과 또는 같은 방향의 방문 위치 중복",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "422", description = "현재 노선에서 승차할 수 없는 정류장",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> select(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal principal,
        @RequestBody SaveFavoriteSelectionRequest request
    ) {
        if (request == null) {
            throw new InvalidFavoriteRequestException();
        }
        selections.select(principal.publicId(),
            groups.targetOf(request.routeId(), request.stopId(), request.direction()), request.favoriteGroupIds());
        return noContent();
    }

    @DeleteMapping("/favorite-groups/{groupId}/favorites/{favoriteId}")
    @Operation(operationId = "deleteMyFavoriteItem", summary = "그룹의 노선 정류장 항목 삭제")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "삭제 완료. 이미 없으면 같은 결과 반환"),
        @ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "내 즐겨찾기 그룹을 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> delete(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal principal,
        @Parameter(description = "내 즐겨찾기 그룹 ID") @PathVariable UUID groupId,
        @Parameter(description = "삭제할 노선 정류장 항목 ID") @PathVariable UUID favoriteId
    ) {
        selections.delete(principal.publicId(), groupId, favoriteId);
        return noContent();
    }

    @PutMapping("/favorite-groups/{groupId}/favorites/order")
    @Operation(operationId = "reorderMyFavoriteItems", summary = "그룹 안의 노선 정류장 항목 순서 변경",
        description = "그룹의 현재 항목 ID를 원하는 순서로 빠짐없이 한 번씩 보내야 합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "순서 변경 완료"),
        @ApiResponse(responseCode = "400", description = "항목 ID가 누락되거나 중복된 요청",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "내 즐겨찾기 그룹을 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> reorder(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal principal,
        @Parameter(description = "내 즐겨찾기 그룹 ID") @PathVariable UUID groupId,
        @RequestBody ReorderFavoritesRequest request
    ) {
        if (request == null) {
            throw new InvalidFavoriteRequestException();
        }
        selections.reorder(principal.publicId(), groupId, request.favoriteIds());
        return noContent();
    }

    private static ResponseEntity<Void> noContent() {
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
