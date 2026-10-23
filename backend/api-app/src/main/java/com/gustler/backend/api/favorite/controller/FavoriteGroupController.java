package com.gustler.backend.api.favorite.controller;

import com.gustler.backend.api.account.MemberPrincipal;
import com.gustler.backend.api.docs.ApiDocsTags;
import com.gustler.backend.api.error.ErrorResponse;
import com.gustler.backend.api.favorite.InvalidFavoriteRequestException;
import com.gustler.backend.api.favorite.application.FavoriteGroupService;
import com.gustler.backend.api.favorite.domain.FavoriteTarget;
import com.gustler.backend.api.favorite.dto.CreateFavoriteGroupRequest;
import com.gustler.backend.api.favorite.dto.FavoriteGroupListResponse;
import com.gustler.backend.api.favorite.dto.FavoriteGroupResponse;
import com.gustler.backend.api.favorite.dto.FavoriteTargetRequest;
import com.gustler.backend.api.favorite.dto.RenameFavoriteGroupRequest;
import com.gustler.backend.api.favorite.dto.ReorderFavoriteGroupsRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = ApiDocsTags.FAVORITES)
@RequestMapping("/api/me/favorite-groups")
@ConditionalOnProperty(name = "auth.enabled", havingValue = "true")
public class FavoriteGroupController {
    private final FavoriteGroupService service;

    public FavoriteGroupController(FavoriteGroupService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(operationId = "getMyFavoriteGroups", summary = "내 즐겨찾기 그룹 조회",
        description = "그룹과 각 그룹의 노선 정류장 항목을 저장된 순서대로 반환합니다. "
            + "첫 번째 그룹이 대표 그룹입니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "즐겨찾기 그룹 목록",
            headers = @Header(name = "Cache-Control", description = "회원별 응답이므로 no-store",
                schema = @Schema(type = "string")),
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = FavoriteGroupListResponse.class))),
        @ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<FavoriteGroupListResponse> findAll(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal principal
    ) {
        return ok(FavoriteGroupListResponse.from(service.findAll(principal.publicId())));
    }

    @PostMapping
    @Operation(operationId = "createMyFavoriteGroup", summary = "즐겨찾기 그룹 생성",
        description = "그룹은 회원당 최대 3개입니다. "
            + "initialFavorite을 보내면 첫 항목을 같은 트랜잭션에서 저장합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "생성된 즐겨찾기 그룹",
            headers = @Header(name = "Cache-Control", description = "회원별 응답이므로 no-store",
                schema = @Schema(type = "string")),
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = FavoriteGroupResponse.class))),
        @ApiResponse(responseCode = "400", description = "이름이나 요청 형식이 올바르지 않음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "그룹 개수 제한, 이름 중복, 정류장 방문 중복",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "422", description = "현재 노선에서 승차할 수 없는 정류장",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<FavoriteGroupResponse> create(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal principal,
        @RequestBody CreateFavoriteGroupRequest request
    ) {
        if (request == null) {
            throw new InvalidFavoriteRequestException();
        }
        FavoriteTarget initialFavorite = targetOf(request.initialFavorite());
        FavoriteGroupResponse response = FavoriteGroupResponse.from(
            service.create(principal.publicId(), request.name(), initialFavorite));
        return ResponseEntity.created(URI.create("/api/me/favorite-groups/" + response.id()))
            .cacheControl(CacheControl.noStore())
            .body(response);
    }

    @PatchMapping("/{groupId}")
    @Operation(operationId = "renameMyFavoriteGroup", summary = "즐겨찾기 그룹 이름 변경")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "이름이 변경된 즐겨찾기 그룹",
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = FavoriteGroupResponse.class))),
        @ApiResponse(responseCode = "400", description = "이름이나 요청 형식이 올바르지 않음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "내 즐겨찾기 그룹을 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "같은 이름의 그룹이 이미 있음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<FavoriteGroupResponse> rename(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal principal,
        @Parameter(description = "내 즐겨찾기 그룹 ID") @PathVariable UUID groupId,
        @RequestBody RenameFavoriteGroupRequest request
    ) {
        if (request == null) {
            throw new InvalidFavoriteRequestException();
        }
        return ok(FavoriteGroupResponse.from(service.rename(principal.publicId(), groupId, request.name())));
    }

    @DeleteMapping("/{groupId}")
    @Operation(operationId = "deleteMyFavoriteGroup", summary = "즐겨찾기 그룹 삭제",
        description = "그룹 안의 노선 정류장 항목도 함께 삭제합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "삭제 완료"),
        @ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "내 즐겨찾기 그룹을 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409",
            description = "첫 번째 순서인 대표 즐겨찾기 그룹은 삭제할 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> delete(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal principal,
        @Parameter(description = "내 즐겨찾기 그룹 ID") @PathVariable UUID groupId
    ) {
        service.delete(principal.publicId(), groupId);
        return noContent();
    }

    @PutMapping("/order")
    @Operation(operationId = "reorderMyFavoriteGroups", summary = "즐겨찾기 그룹 순서 변경",
        description = "현재 내 그룹 ID를 원하는 순서로 빠짐없이 한 번씩 보내야 합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "순서 변경 완료"),
        @ApiResponse(responseCode = "400", description = "그룹 ID가 누락되거나 중복된 요청",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> reorder(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal principal,
        @RequestBody ReorderFavoriteGroupsRequest request
    ) {
        if (request == null) {
            throw new InvalidFavoriteRequestException();
        }
        service.reorder(principal.publicId(), request.groupIds());
        return noContent();
    }

    private FavoriteTarget targetOf(FavoriteTargetRequest request) {
        if (request == null) {
            return null;
        }
        return service.targetOf(request.routeId(), request.stopId(), request.direction());
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static ResponseEntity<Void> noContent() {
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
