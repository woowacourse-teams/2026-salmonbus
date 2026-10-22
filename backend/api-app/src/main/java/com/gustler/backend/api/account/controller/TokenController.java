package com.gustler.backend.api.account.controller;

import com.gustler.backend.api.account.dto.RefreshRequest;
import com.gustler.backend.api.account.dto.TokenResponse;
import com.gustler.backend.api.account.token.AccessTokens;
import com.gustler.backend.api.account.token.AuthCookies;
import com.gustler.backend.api.account.token.BearerTokens;
import com.gustler.backend.api.docs.ApiDocsExamples;
import com.gustler.backend.api.docs.ApiDocsTags;
import com.gustler.backend.api.docs.AuthApiDocs;
import com.gustler.backend.api.docs.AuthApiDocsExamples;
import com.gustler.backend.api.error.ErrorResponse;
import com.gustler.backend.api.error.UnauthenticatedException;
import com.gustler.backend.member.api.RefreshResult;
import com.gustler.backend.member.api.RefreshTokens;
import io.micrometer.core.instrument.MeterRegistry;
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
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 리프레시 토큰으로 로그인을 이어 가거나 끝낸다.
 *
 * <p>리프레시 토큰은 본문의 {@code refreshToken} 을 먼저 보고 없으면 쿠키를 본다. 쿠키로 보낸 요청에는 쿠키로, 본문으로 보낸
 * 요청에는 본문으로 새 토큰을 돌려준다. 갱신에 실패하면 어떤 이유든 401 {@code UNAUTHENTICATED} 이고, 쿠키로 보낸 요청에는
 * 인증 쿠키를 지운다. 실패 이유를 구분해 알리지 않는다.
 *
 * <p>{@code auth.enabled} 가 {@code true} 일 때만 등록한다.
 */
@RestController
@Tag(name = ApiDocsTags.AUTH)
@RequestMapping("/api/auth")
@ConditionalOnProperty(name = "auth.enabled", havingValue = "true")
public class TokenController {
    private static final Logger LOGGER = LoggerFactory.getLogger(TokenController.class);

    private final RefreshTokens refreshTokens;
    private final MeterRegistry meters;
    private final AccessTokens accessTokens;
    private final AuthCookies authCookies;

    public TokenController(
        RefreshTokens refreshTokens,
        AccessTokens accessTokens,
        AuthCookies authCookies,
        MeterRegistry meters
    ) {
        this.refreshTokens = refreshTokens;
        this.meters = meters;
        this.accessTokens = accessTokens;
        this.authCookies = authCookies;
    }

    @PostMapping("/refresh")
    @Operation(operationId = "refreshAuthTokens", summary = "리프레시 토큰으로 로그인 연장",
        description = """
            리프레시 토큰을 다음 세대로 바꾸고 액세스 토큰을 새로 발급합니다.
            본문의 `refreshToken`을 먼저 보고, 없거나 비어 있으면 `__Host-sb_rt` 쿠키를 봅니다. 본문은 생략할 수 있습니다.

            쿠키로 보낸 요청(웹·웹뷰)은 204와 함께 `__Host-sb_at`(15분)·`__Host-sb_rt` 쿠키를 새로 받습니다.
            리프레시 토큰은 갱신할 때마다 다시 30일, 처음 로그인한 뒤 최대 180일 유효합니다.
            본문으로 보낸 요청(쿠키를 쓰지 않는 클라이언트)은 200 본문으로 새 토큰을 받습니다.
            리프레시 토큰은 갱신할 때마다 바뀌므로 받은 값으로 바꿔 저장합니다.
            직전 세대 토큰을 갱신 뒤 10초 안에 다시 보내면 동시 갱신으로 보고 지금 토큰을 다시 줍니다.
            10초가 지났거나 그보다 오래된 세대 토큰이면 그 로그인 계통 전체를 폐기하고 401입니다.

            갱신에 실패하면 이유와 관계없이 401 `UNAUTHENTICATED`이고, 쿠키로 보낸 요청이면 인증 쿠키 두 개를 지웁니다.
            """ + AuthApiDocs.CSRF_RULE,
        parameters = @Parameter(name = AuthApiDocs.CSRF_HEADER, in = ParameterIn.HEADER,
            description = AuthApiDocs.CSRF_HEADER_DESCRIPTION, schema = @Schema(type = "string")))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "본문으로 보낸 요청의 새 토큰",
            headers =
            @Header(name = "Cache-Control", description = AuthApiDocs.NO_STORE, schema = @Schema(type = "string")),
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = TokenResponse.class),
                examples = @ExampleObject(name = "token", value = AuthApiDocsExamples.TOKEN))),
        @ApiResponse(responseCode = "204", description = "쿠키로 보낸 요청의 새 토큰. 본문 없음", content = @Content,
            headers = {
                @Header(name = "Cache-Control", description = AuthApiDocs.NO_STORE, schema = @Schema(type = "string")),
                @Header(name = "Set-Cookie",
                    description = "__Host-sb_at(Max-Age 900)과 __Host-sb_rt(Max-Age는 리프레시 토큰 만료까지, "
                        + "갱신할 때마다 다시 30일, 처음 로그인한 뒤 최대 180일). "
                        + "둘 다 HttpOnly; Secure; SameSite=Lax; Path=/",
                    schema = @Schema(type = "string"))
            }),
        @ApiResponse(responseCode = "400", description = "CSRF 토큰 확인 실패 또는 본문 형식 오류",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = {
                    @ExampleObject(name = "csrfRejected", value = AuthApiDocsExamples.CSRF_REJECTED),
                    @ExampleObject(name = "invalidRequest", value = ApiDocsExamples.INVALID_REQUEST)
                })),
        @ApiResponse(responseCode = "401",
            description = "리프레시 토큰이 없거나 만료·폐기·위조됨. 쿠키로 보낸 요청이면 인증 쿠키 두 개를 지움",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(name = "unauthenticated", value = AuthApiDocsExamples.UNAUTHENTICATED)))
    })
    public ResponseEntity<TokenResponse> refresh(
        @RequestBody(required = false) RefreshRequest body,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        Optional<String> fromBody = bodyToken(body);
        Optional<String> token = fromBody.or(() -> BearerTokens.refreshCookie(request));
        RefreshResult result = rotate(token);
        if (!(result instanceof RefreshResult.Rotated rotated)) {
            record(result);
            if (fromBody.isEmpty()) {
                clearCookies(response);
            }
            throw new UnauthenticatedException();
        }
        String accessToken = accessTokens.issue(rotated.publicId());
        if (fromBody.isPresent()) {
            return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new TokenResponse(
                    accessToken, accessTokens.accessTtlSeconds(), rotated.next().token(), rotated.next().expiresAt()));
        }
        HttpHeaders headers = new HttpHeaders();
        authCookies.write(headers, accessToken, rotated.next());
        return ResponseEntity.noContent().headers(headers).cacheControl(CacheControl.noStore()).build();
    }

    /** 이 로그인 계통의 리프레시 토큰을 모두 폐기하고 인증 쿠키를 지운다. 토큰이 없어도 204 다. */
    @PostMapping("/logout")
    @Operation(operationId = "logout", summary = "로그아웃",
        description = """
            본문의 `refreshToken` 또는 `__Host-sb_rt` 쿠키가 속한 로그인 계통을 폐기하고 인증 쿠키 두 개를 지웁니다.
            토큰이 없거나 이미 폐기됐어도 204입니다. 본문은 생략할 수 있습니다.
            이미 발급한 액세스 토큰은 만료(최대 15분)까지 취소되지 않으므로, 쿠키를 쓰지 않는 클라이언트는 보관한 토큰을 직접 버립니다.

            """ + AuthApiDocs.CSRF_RULE,
        parameters = @Parameter(name = AuthApiDocs.CSRF_HEADER, in = ParameterIn.HEADER,
            description = AuthApiDocs.CSRF_HEADER_DESCRIPTION, schema = @Schema(type = "string")))
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "로그아웃함. 본문 없음", content = @Content,
            headers = {
                @Header(name = "Cache-Control", description = AuthApiDocs.NO_STORE, schema = @Schema(type = "string")),
                @Header(name = "Set-Cookie", description = AuthApiDocs.CLEARED_COOKIES,
                    schema = @Schema(type = "string"))
            }),
        @ApiResponse(responseCode = "400", description = "CSRF 토큰 확인 실패 또는 본문 형식 오류",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = {
                    @ExampleObject(name = "csrfRejected", value = AuthApiDocsExamples.CSRF_REJECTED),
                    @ExampleObject(name = "invalidRequest", value = ApiDocsExamples.INVALID_REQUEST)
                }))
    })
    public ResponseEntity<Void> logout(
        @RequestBody(required = false) RefreshRequest body,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        bodyToken(body).or(() -> BearerTokens.refreshCookie(request)).ifPresent(refreshTokens::revoke);
        clearCookies(response);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /**
     * 갱신이 거절된 이유를 로그와 지표에 남긴다. 클라이언트에는 이유를 알리지 않으므로 운영자가 볼 수 있는 곳은 여기뿐이다.
     * 재사용({@code REUSED}) 탐지는 탈취 신호일 수 있어 경고로 남기고, 토큰 값은 남기지 않는다.
     */
    private void record(RefreshResult result) {
        RefreshResult.Reason reason = ((RefreshResult.Rejected) result).reason();
        meters.counter("auth.refresh.rejected", "reason", reason.name()).increment();
        if (reason == RefreshResult.Reason.REUSED) {
            LOGGER.warn("이전 세대 리프레시 토큰이 다시 제시되어 로그인 계통을 폐기했다");
        }
    }

    private RefreshResult rotate(Optional<String> token) {
        if (token.isEmpty()) {
            return new RefreshResult.Rejected(RefreshResult.Reason.UNKNOWN);
        }
        return refreshTokens.rotate(token.get());
    }

    private static Optional<String> bodyToken(RefreshRequest body) {
        if (body == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(body.refreshToken()).filter(token -> !token.isBlank());
    }

    private void clearCookies(HttpServletResponse response) {
        HttpHeaders headers = new HttpHeaders();
        authCookies.clear(headers);
        headers.forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
    }
}
