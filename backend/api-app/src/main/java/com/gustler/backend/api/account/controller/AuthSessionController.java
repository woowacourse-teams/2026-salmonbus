package com.gustler.backend.api.account.controller;

import com.gustler.backend.api.account.dto.AuthSessionResponse;
import com.gustler.backend.api.account.dto.MemberResponse;
import com.gustler.backend.api.account.token.AccessTokens;
import com.gustler.backend.api.account.token.BearerTokens;
import com.gustler.backend.api.docs.ApiDocsTags;
import com.gustler.backend.api.docs.AuthApiDocs;
import com.gustler.backend.api.docs.AuthApiDocsExamples;
import com.gustler.backend.member.api.ViewMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 현재 로그인 상태를 알려 주고 CSRF 토큰 쿠키를 발급한다.
 *
 * <p>상태를 바꾸는 요청을 보내기 전에 클라이언트가 이 경로를 호출해 {@code __Host-XSRF-TOKEN} 쿠키를 받아야 한다.
 * 만료되거나 깨진 토큰이 와도 401 을 내지 않고 로그인하지 않은 것으로 답한다. 토큰의 회원이 이미 탈퇴했어도 같다.
 * {@code auth.enabled} 가 {@code true} 일 때만 등록한다.
 */
@RestController
@Tag(name = ApiDocsTags.AUTH)
@ConditionalOnProperty(name = "auth.enabled", havingValue = "true")
public class AuthSessionController {
    private final ViewMember viewMember;
    private final AccessTokens accessTokens;

    public AuthSessionController(ViewMember viewMember, AccessTokens accessTokens) {
        this.viewMember = viewMember;
        this.accessTokens = accessTokens;
    }

    @GetMapping("/api/auth/session")
    @Operation(operationId = "getAuthSession", summary = "로그인 상태와 CSRF 토큰 쿠키",
        description = """
            현재 로그인 상태를 반환합니다. CSRF 토큰 쿠키 `__Host-XSRF-TOKEN`이 없으면 이 응답에서 발급합니다.
            상태를 바꾸는 요청(갱신·로그아웃·모든 기기 로그아웃·닉네임 변경·탈퇴)을 보내기 전에 호출하고, 쿠키 값을 `X-XSRF-TOKEN` 헤더에 그대로 싣습니다.

            """ + AuthApiDocs.ACCESS_TOKEN_RULE + """

            토큰이 없거나 만료·위조됐거나 회원이 탈퇴했으면 401 대신 `authenticated=false`로 답합니다.
            이때 `__Host-sb_rt` 쿠키가 있으면 `refreshable=true`이므로 `POST /api/auth/refresh`로 로그인을 이어 갑니다.
            리프레시 토큰 쿠키는 스크립트가 읽을 수 없어 이 값으로 판단합니다. 리프레시 토큰이 유효한지는 확인하지 않습니다.

            로그인이 꺼져 있으면(`AUTH_ENABLED=false`) 이 경로는 404이고 명세에도 나오지 않습니다.
            """)
    @ApiResponses(
        @ApiResponse(responseCode = "200", description = "로그인 상태. 로그인하지 않았으면 member를 생략",
            headers = {
                @Header(name = "Cache-Control", description = "no-store", schema = @Schema(type = "string")),
                @Header(name = "Set-Cookie",
                    description = "CSRF 토큰 쿠키가 없을 때만 __Host-XSRF-TOKEN 발급. Secure; SameSite=Lax; Path=/. "
                        + "스크립트가 읽을 수 있음(HttpOnly 아님)",
                    schema = @Schema(type = "string"))
            },
            content =
            @Content(mediaType = "application/json", schema = @Schema(implementation = AuthSessionResponse.class),
                examples = {
                    @ExampleObject(name = "authenticated", summary = "로그인함",
                        value = AuthApiDocsExamples.SESSION_AUTHENTICATED),
                    @ExampleObject(name = "refreshable", summary = "액세스 토큰 없음·리프레시 토큰 쿠키 있음",
                        value = AuthApiDocsExamples.SESSION_REFRESHABLE),
                    @ExampleObject(name = "anonymous", summary = "로그인하지 않음",
                        value = AuthApiDocsExamples.SESSION_ANONYMOUS)
                })))
    public ResponseEntity<AuthSessionResponse> session(
        @Parameter(hidden = true) CsrfToken csrfToken,
        HttpServletRequest request
    ) {
        csrfToken.getToken();
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(responseOf(request));
    }

    private AuthSessionResponse responseOf(HttpServletRequest request) {
        Optional<UUID> memberId = BearerTokens.access(request).flatMap(accessTokens::verify);
        Optional<MemberResponse> member = memberId.flatMap(viewMember::view).map(MemberResponse::from);
        if (member.isPresent()) {
            return AuthSessionResponse.of(member.get());
        }
        return AuthSessionResponse.anonymous(BearerTokens.refreshCookie(request).isPresent());
    }
}
