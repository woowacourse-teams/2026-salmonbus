package com.gustler.backend.api.push.controller;

import com.gustler.backend.api.account.MemberPrincipal;
import com.gustler.backend.api.docs.ApiDocsExamples;
import com.gustler.backend.api.docs.ApiDocsTags;
import com.gustler.backend.api.docs.AuthApiDocs;
import com.gustler.backend.api.docs.AuthApiDocsExamples;
import com.gustler.backend.api.error.ErrorResponse;
import com.gustler.backend.api.push.application.NotificationSettingRepository.SavedSetting;
import com.gustler.backend.api.push.application.NotificationSettingService;
import com.gustler.backend.api.push.dto.NotificationSettingResponse;
import com.gustler.backend.api.push.dto.NotificationSettingsResponse;
import com.gustler.backend.api.push.dto.SaveNotificationSettingRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = ApiDocsTags.NOTIFICATIONS)
@RequestMapping("/api/me/notification-settings")
@ConditionalOnProperty(name = "auth.enabled", havingValue = "true")
public class NotificationSettingController {
    private final NotificationSettingService service;

    public NotificationSettingController(NotificationSettingService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(operationId = "listMyNotificationSettings", summary = "내 알림 설정 조회",
        description = "저장한 알림 설정을 모두 반환합니다. "
            + AuthApiDocs.ACCESS_TOKEN_RULE)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "알림 설정 목록",
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = NotificationSettingsResponse.class)),
            headers = @Header(name = "Cache-Control", description = AuthApiDocs.NO_STORE,
                schema = @Schema(type = "string"))),
        @ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음, 또는 탈퇴한 회원",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(name = "unauthenticated", value = AuthApiDocsExamples.UNAUTHENTICATED)))
    })
    public ResponseEntity<NotificationSettingsResponse> list(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal principal
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new NotificationSettingsResponse(service.list(principal.publicId()).stream()
                .map(NotificationSettingController::toResponse)
                .toList()));
    }

    @PutMapping
    @Operation(operationId = "saveMyNotificationSetting", summary = "알림 설정 저장",
        description = "즐겨찾기 대상마다 시각과 요일을 저장하고 다음 차례 시각을 계산합니다. "
            + AuthApiDocs.ACCESS_TOKEN_RULE + " " + AuthApiDocs.CSRF_RULE,
        parameters = @Parameter(name = AuthApiDocs.CSRF_HEADER, in = ParameterIn.HEADER,
            description = AuthApiDocs.CSRF_HEADER_DESCRIPTION, schema = @Schema(type = "string")))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "저장된 알림 설정",
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = NotificationSettingResponse.class)),
            headers = @Header(name = "Cache-Control", description = AuthApiDocs.NO_STORE,
                schema = @Schema(type = "string"))),
        @ApiResponse(responseCode = "400", description = "시각과 요일 오류, 대상 형식 오류, CSRF 확인 실패 또는 본문 형식 오류",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = {
                    @ExampleObject(name = "invalidNotificationSchedule",
                        value = AuthApiDocsExamples.INVALID_NOTIFICATION_SCHEDULE),
                    @ExampleObject(name = "csrfRejected", value = AuthApiDocsExamples.CSRF_REJECTED),
                    @ExampleObject(name = "invalidRequest", value = ApiDocsExamples.INVALID_REQUEST)
                })),
        @ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음, 또는 탈퇴한 회원",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(name = "unauthenticated", value = AuthApiDocsExamples.UNAUTHENTICATED))),
        @ApiResponse(responseCode = "422", description = "즐겨찾기에 없는 대상",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(name = "notificationTargetNotFavorite",
                    value = AuthApiDocsExamples.NOTIFICATION_TARGET_NOT_FAVORITE)))
    })
    public ResponseEntity<NotificationSettingResponse> save(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal principal,
        @RequestBody SaveNotificationSettingRequest request
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(toResponse(service.save(
                principal.publicId(),
                request.routeId(),
                request.stopId(),
                request.direction(),
                request.time(),
                request.weekdays()
            )));
    }

    @DeleteMapping("/{id}")
    @Operation(operationId = "deleteMyNotificationSetting", summary = "알림 설정 삭제",
        description = "내 알림 설정만 지웁니다. "
            + AuthApiDocs.ACCESS_TOKEN_RULE + " " + AuthApiDocs.CSRF_RULE,
        parameters = @Parameter(name = AuthApiDocs.CSRF_HEADER, in = ParameterIn.HEADER,
            description = AuthApiDocs.CSRF_HEADER_DESCRIPTION, schema = @Schema(type = "string")))
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "삭제 완료. 본문 없음", content = @Content,
            headers = @Header(name = "Cache-Control", description = AuthApiDocs.NO_STORE,
                schema = @Schema(type = "string"))),
        @ApiResponse(responseCode = "400", description = "식별자 형식 오류 또는 CSRF 확인 실패",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = {
                    @ExampleObject(name = "csrfRejected", value = AuthApiDocsExamples.CSRF_REJECTED),
                    @ExampleObject(name = "invalidRequest", value = ApiDocsExamples.INVALID_REQUEST)
                })),
        @ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음, 또는 탈퇴한 회원",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(name = "unauthenticated", value = AuthApiDocsExamples.UNAUTHENTICATED))),
        @ApiResponse(responseCode = "404", description = "내 알림 설정이 아님",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(name = "notificationSettingNotFound",
                    value = AuthApiDocsExamples.NOTIFICATION_SETTING_NOT_FOUND)))
    })
    public ResponseEntity<Void> delete(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal principal,
        @Parameter(description = "알림 설정 ID") @PathVariable UUID id
    ) {
        service.delete(principal.publicId(), id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static NotificationSettingResponse toResponse(SavedSetting setting) {
        return new NotificationSettingResponse(
            setting.id(),
            setting.routeId(),
            setting.stopId(),
            setting.direction(),
            setting.time(),
            setting.weekdays()
        );
    }
}
