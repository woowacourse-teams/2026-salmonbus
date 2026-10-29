package com.gustler.backend.api.favorite.controller;

import com.gustler.backend.api.account.MemberPrincipal;
import com.gustler.backend.api.docs.ApiDocsTags;
import com.gustler.backend.api.error.ErrorResponse;
import com.gustler.backend.api.favorite.application.FavoriteOverviewService;
import com.gustler.backend.api.favorite.dto.FavoriteOverviewResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = ApiDocsTags.FAVORITES)
@RequestMapping("/api/me/favorites")
@ConditionalOnProperty(name = "auth.enabled", havingValue = "true")
public class FavoriteOverviewController {
    private final FavoriteOverviewService service;

    public FavoriteOverviewController(FavoriteOverviewService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(operationId = "getMyFavoriteOverview", summary = "즐겨찾기별 접근 차량 조회",
        description = "groupId를 생략하면 전체 그룹을 반환합니다. "
            + "보내면 회원이 소유한 해당 그룹 하나만 groups 배열로 반환합니다. "
            + "회원이 정한 순서대로 현재 watch의 정류장 정보와 접근 차량을 반환합니다. "
            + "접근 차량의 필드 이름과 좌석 예보는 watch 계약을 유지하며 ETA만 추가합니다. "
            + "표시 순서와 관계없이 같은 차량 ID의 유효한 ETA를 연결하며, ETA가 없어도 접근 차량은 최대 3대까지 유지합니다. "
            + "한 항목의 실패는 다른 항목을 숨기지 않습니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "즐겨찾기 그룹별 노선 정류장 항목",
            headers = @Header(name = "Cache-Control", description = "회원별 응답이므로 no-store",
                schema = @Schema(type = "string")),
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = FavoriteOverviewResponse.class))),
        @ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음",
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404",
            description = "지정한 즐겨찾기 그룹이 없거나 내 소유가 아님",
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<FavoriteOverviewResponse> get(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal principal,
        @Parameter(description = "조회할 내 즐겨찾기 그룹 ID. 생략하면 전체 그룹 조회")
        @RequestParam(required = false) UUID groupId
    ) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(FavoriteOverviewResponse.from(service.get(principal.publicId(), groupId)));
    }
}
