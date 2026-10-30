package com.gustler.backend.api.http.login;

import com.gustler.backend.api.account.InvalidLoginRequestException;
import com.gustler.backend.api.account.MemberPrincipal;
import com.gustler.backend.api.account.NotAppleMemberException;
import com.gustler.backend.api.account.ReauthRequiredException;
import com.gustler.backend.api.account.dto.AppleAppWithdrawalRequest;
import com.gustler.backend.api.account.token.AuthCookies;
import com.gustler.backend.api.docs.ApiDocsExamples;
import com.gustler.backend.api.docs.ApiDocsTags;
import com.gustler.backend.api.docs.AuthApiDocs;
import com.gustler.backend.api.docs.AuthApiDocsExamples;
import com.gustler.backend.api.error.ErrorResponse;
import com.gustler.backend.api.error.CsrfRejectedException;
import com.gustler.backend.api.error.ServiceUnavailableException;
import com.gustler.backend.api.error.UnauthenticatedException;
import com.gustler.backend.member.api.LoginProvider;
import com.gustler.backend.member.api.MemberIdentity;
import com.gustler.backend.member.api.WithdrawMember;
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
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 앱에서 애플 회원이 Sign in with Apple 로 다시 인증해 받은 인가 코드로 탈퇴한다.
 *
 * <p>로그인한 회원만 부를 수 있고, 인증 쿠키로 오는 변경 요청이라 CSRF 토큰이 필요하다. 탈퇴하면 인증 쿠키를 지우고 204 다.
 * 코드가 틀렸거나 만료됐거나, 다른 애플 계정이거나, 애플 토큰을 취소하지 못하면 회원과 쿠키를 그대로 두고 409
 * {@code REAUTH_REQUIRED} 로 답한다. 앱은 다시 인증해 새 코드로 시도한다. 애플이 응답하지 않으면 503 이다. 카카오 회원은
 * 400 {@code INVALID_REQUEST} 다. 애플 앱 로그인이 켜지지 않았으면 404 다.
 */
@RestController
@Tag(name = ApiDocsTags.MEMBER)
@ConditionalOnProperty(name = "auth.enabled", havingValue = "true")
public class AppleAppWithdrawalController {
    private final AppLogin appLogin;
    private final WithdrawMember withdrawMember;
    private final AuthCookies authCookies;

    AppleAppWithdrawalController(AppLogin appLogin, WithdrawMember withdrawMember, AuthCookies authCookies) {
        this.appLogin = appLogin;
        this.withdrawMember = withdrawMember;
        this.authCookies = authCookies;
    }

    @PostMapping("/api/me/withdrawal/apple/native")
    @Operation(operationId = "withdrawAppleFromApp", summary = "회원 탈퇴(애플, 앱)",
        description = """
            앱의 애플 회원이 Sign in with Apple로 다시 인증해 받은 `authorizationCode`로 탈퇴합니다.
            서버가 코드를 앱 `client_id`로 교환해 다시 인증한 계정이 이 회원인지 확인하고, 애플 토큰을 취소한 뒤 회원을 지웁니다.
            탈퇴하면 인증 쿠키 두 개를 지우고 204로 답합니다.
            - 코드가 틀렸거나 만료됐거나(5분, 1회), 다른 애플 계정이거나, 애플 토큰을 취소하지 못하면 409 `REAUTH_REQUIRED`입니다.
              회원과 인증 쿠키는 그대로이므로 다시 인증해 새 코드로 시도합니다
            - 애플이 응답하지 않으면 503 `SERVICE_UNAVAILABLE`이고 회원은 그대로입니다
            - 카카오 회원이거나 본문에 코드가 없으면 400 `INVALID_REQUEST`입니다. 카카오 회원은 `DELETE /api/me`로 탈퇴합니다
            - 애플 앱 로그인이 켜지지 않았으면 404 `ENDPOINT_NOT_FOUND`입니다

            """ + AuthApiDocs.ACCESS_TOKEN_RULE + " " + AuthApiDocs.CSRF_RULE,
        parameters = @Parameter(name = AuthApiDocs.CSRF_HEADER, in = ParameterIn.HEADER,
            description = AuthApiDocs.CSRF_HEADER_DESCRIPTION, schema = @Schema(type = "string")))
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "탈퇴함. 본문 없음", content = @Content,
            headers = {
                @Header(name = "Cache-Control", description = AuthApiDocs.NO_STORE,
                    schema = @Schema(type = "string")),
                @Header(name = "Set-Cookie", description = AuthApiDocs.CLEARED_COOKIES,
                    schema = @Schema(type = "string"))
            }),
        @ApiResponse(responseCode = "400", description = "카카오 회원, 코드 없음, CSRF 토큰 확인 실패 또는 사이트가 아닌 출처",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = {
                    @ExampleObject(name = "invalidRequest", value = ApiDocsExamples.INVALID_REQUEST),
                    @ExampleObject(name = "csrfRejected", value = AuthApiDocsExamples.CSRF_REJECTED)
                })),
        @ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 만료·위조됨, 또는 탈퇴한 회원",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(name = "unauthenticated", value = AuthApiDocsExamples.UNAUTHENTICATED))),
        @ApiResponse(responseCode = "404", description = "애플 앱 로그인이 켜지지 않음",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(name = "endpointNotFound", value = AuthApiDocsExamples.ENDPOINT_NOT_FOUND))),
        @ApiResponse(responseCode = "409", description = "다시 인증한 코드로 탈퇴하지 못함. 회원은 그대로",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(name = "reauthRequired", value = AuthApiDocsExamples.REAUTH_REQUIRED))),
        @ApiResponse(responseCode = "503", description = "애플이 응답하지 않음. 회원은 그대로",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(name = "serviceUnavailable", value = ApiDocsExamples.SERVICE_UNAVAILABLE)))
    })
    public ResponseEntity<Void> withdraw(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal principal,
        @RequestBody(required = false) AppleAppWithdrawalRequest body,
        HttpServletRequest request
    ) {
        AppleAppWithdrawal withdrawal = appLogin.appleWithdrawal()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!appLogin.fromSite(request)) {
            throw new CsrfRejectedException();
        }
        if (body == null || body.authorizationCode() == null || body.authorizationCode().isBlank()) {
            throw new InvalidLoginRequestException();
        }
        MemberIdentity identity = withdrawMember.identityOf(principal.publicId())
            .orElseThrow(UnauthenticatedException::new);
        if (identity.provider() != LoginProvider.APPLE) {
            throw new NotAppleMemberException();
        }
        return switch (withdrawal.withdraw(principal.publicId(), body.authorizationCode())) {
            case WITHDRAWN -> {
                HttpHeaders headers = new HttpHeaders();
                authCookies.clear(headers);
                yield ResponseEntity.noContent().headers(headers).cacheControl(CacheControl.noStore()).build();
            }
            case REAUTH_FAILED -> throw new ReauthRequiredException();
            case UNAVAILABLE -> throw new ServiceUnavailableException();
        };
    }
}
