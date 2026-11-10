package com.gustler.backend.api.push.controller;

import com.gustler.backend.api.account.MemberPrincipal;
import com.gustler.backend.api.docs.ApiDocsExamples;
import com.gustler.backend.api.docs.ApiDocsTags;
import com.gustler.backend.api.docs.AuthApiDocs;
import com.gustler.backend.api.docs.AuthApiDocsExamples;
import com.gustler.backend.api.error.ErrorResponse;
import com.gustler.backend.api.push.application.PushDeviceService;
import com.gustler.backend.api.push.dto.RegisterPushDeviceRequest;
import com.gustler.backend.api.push.dto.UnregisterPushDeviceRequest;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = ApiDocsTags.PUSH)
@RequestMapping("/api/me/push-devices")
@ConditionalOnProperty(name = "auth.enabled", havingValue = "true")
public class PushDeviceController {
    private final PushDeviceService service;

    public PushDeviceController(PushDeviceService service) {
        this.service = service;
    }

    @PutMapping
    @Operation(operationId = "registerMyPushDevice", summary = "푸시 기기 등록",
        description = "APNs 기기 토큰을 등록합니다. 같은 토큰은 현재 회원으로 이동합니다. "
            + "토큰은 1~200자의 16진수이며 환경은 SANDBOX 또는 PRODUCTION입니다. "
            + AuthApiDocs.ACCESS_TOKEN_RULE + " " + AuthApiDocs.CSRF_RULE,
        parameters = @Parameter(name = AuthApiDocs.CSRF_HEADER, in = ParameterIn.HEADER,
            description = AuthApiDocs.CSRF_HEADER_DESCRIPTION, schema = @Schema(type = "string")))
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "기기 등록 완료. 본문 없음", content = @Content,
            headers = @Header(name = "Cache-Control", description = AuthApiDocs.NO_STORE,
                schema = @Schema(type = "string"))),
        @ApiResponse(responseCode = "400", description = "기기 필드 오류, CSRF 확인 실패 또는 본문 형식 오류",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = {
                    @ExampleObject(name = "invalidPushDevice", value = AuthApiDocsExamples.INVALID_PUSH_DEVICE),
                    @ExampleObject(name = "csrfRejected", value = AuthApiDocsExamples.CSRF_REJECTED),
                    @ExampleObject(name = "invalidRequest", value = ApiDocsExamples.INVALID_REQUEST)
                })),
        @ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음, 또는 탈퇴한 회원",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(name = "unauthenticated", value = AuthApiDocsExamples.UNAUTHENTICATED)))
    })
    public ResponseEntity<Void> register(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal principal,
        @RequestBody RegisterPushDeviceRequest request
    ) {
        service.register(principal.publicId(), request.token(), request.environment());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/unregister")
    @Operation(operationId = "unregisterMyPushDevice", summary = "푸시 기기 등록 해제",
        description = "내 기기 토큰만 지웁니다. 없거나 다른 회원의 토큰이어도 204로 답합니다. "
            + AuthApiDocs.ACCESS_TOKEN_RULE + " " + AuthApiDocs.CSRF_RULE,
        parameters = @Parameter(name = AuthApiDocs.CSRF_HEADER, in = ParameterIn.HEADER,
            description = AuthApiDocs.CSRF_HEADER_DESCRIPTION, schema = @Schema(type = "string")))
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "기기 등록 해제 완료. 본문 없음", content = @Content,
            headers = @Header(name = "Cache-Control", description = AuthApiDocs.NO_STORE,
                schema = @Schema(type = "string"))),
        @ApiResponse(responseCode = "400", description = "기기 토큰 오류, CSRF 확인 실패 또는 본문 형식 오류",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = {
                    @ExampleObject(name = "invalidPushDevice", value = AuthApiDocsExamples.INVALID_PUSH_DEVICE),
                    @ExampleObject(name = "csrfRejected", value = AuthApiDocsExamples.CSRF_REJECTED),
                    @ExampleObject(name = "invalidRequest", value = ApiDocsExamples.INVALID_REQUEST)
                })),
        @ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음, 또는 탈퇴한 회원",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(name = "unauthenticated", value = AuthApiDocsExamples.UNAUTHENTICATED)))
    })
    public ResponseEntity<Void> unregister(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal principal,
        @RequestBody UnregisterPushDeviceRequest request
    ) {
        service.unregister(principal.publicId(), request.token());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
