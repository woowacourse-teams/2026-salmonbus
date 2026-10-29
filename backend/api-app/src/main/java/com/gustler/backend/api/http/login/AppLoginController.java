package com.gustler.backend.api.http.login;

import com.gustler.backend.api.account.InvalidLoginRequestException;
import com.gustler.backend.api.account.dto.AppLoginNonceResponse;
import com.gustler.backend.api.account.dto.AppLoginRequest;
import com.gustler.backend.api.docs.ApiDocsExamples;
import com.gustler.backend.api.docs.ApiDocsTags;
import com.gustler.backend.api.docs.AuthApiDocs;
import com.gustler.backend.api.docs.AuthApiDocsExamples;
import com.gustler.backend.api.error.ErrorResponse;
import com.gustler.backend.api.error.CsrfRejectedException;
import com.gustler.backend.api.error.ServiceUnavailableException;
import com.gustler.backend.api.error.UnauthenticatedException;
import com.gustler.backend.api.http.login.AppIdTokenVerifier.Verification;
import com.gustler.backend.api.monitoring.UserMonitoring;
import com.gustler.backend.api.monitoring.UserMonitoring.LoginResult;
import com.gustler.backend.api.monitoring.UserMonitoring.MemberKind;
import com.gustler.backend.member.api.LoginProvider;
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
import jakarta.servlet.http.HttpSession;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 앱이 제공자 SDK 로 받은 ID 토큰으로 로그인시킨다. 웹이 앱 WebView 안에서 부르므로 인증 쿠키가 WebView 에 저장된다.
 *
 * <p>순서는 nonce 발급 → 앱이 SDK 에 nonce 를 넣어 로그인 → 받은 ID 토큰으로 로그인이다. nonce 는 임시 세션에 두고 한
 * 번만 쓴다({@link AppLoginNonces}). 세션에 nonce 가 없으면 토큰을 열어 보지 않고 거절해, 위조 토큰이 제공자 공개키
 * 조회를 일으키지 않게 한다. 회원 처리는 웹 콜백과 같은 {@link LoginCompletion} 이다.
 *
 * <p>켜지지 않은 제공자(스위치가 꺼졌거나 앱용 대상 값이 없음)는 404 다. 토큰 검증 실패는 이유를 나누지 않고 401
 * {@code UNAUTHENTICATED}, 제공자 공개키를 받지 못하면 503 이다. 출처가 사이트가 아니면 400 {@code CSRF_REJECTED} 다.
 * 로그인하면 임시 세션을 지운다. {@code auth.enabled} 가 {@code true} 일 때만 등록한다.
 */
@RestController
@Tag(name = ApiDocsTags.AUTH)
@ConditionalOnProperty(name = "auth.enabled", havingValue = "true")
public class AppLoginController {
    private static final Logger LOGGER = LoggerFactory.getLogger(AppLoginController.class);

    private final AppLogin appLogin;
    private final AppLoginNonces nonces;
    private final LoginCompletion completion;
    private final UserMonitoring monitoring;

    AppLoginController(
        AppLogin appLogin,
        AppLoginNonces nonces,
        LoginCompletion completion,
        UserMonitoring monitoring
    ) {
        this.appLogin = appLogin;
        this.nonces = nonces;
        this.completion = completion;
        this.monitoring = monitoring;
    }

    @PostMapping("/api/auth/{provider}/native/nonce")
    @Operation(operationId = "issueAppLoginNonce", summary = "앱 로그인 nonce 발급",
        description = """
            앱 로그인에 한 번만 쓸 nonce를 발급합니다. 앱 WebView 안의 웹이 부릅니다.
            nonce는 이 브라우저의 로그인 중 임시 세션(`__Host-sb_session`, 10분)에 저장되므로 같은 WebView에서 로그인을 요청해야 합니다.
            - 애플: 이 값의 SHA-256(UTF-8, 소문자 16진수)을 Sign in with Apple의 nonce로 넘깁니다
            - 카카오: 이 값을 그대로 카카오 SDK의 nonce로 넘깁니다
            - 같은 제공자로 다시 받으면 앞의 nonce는 쓸 수 없습니다

            `provider`가 `apple`·`kakao`가 아니거나 그 제공자의 앱 로그인이 켜지지 않았으면 404 `ENDPOINT_NOT_FOUND`입니다.

            """ + AuthApiDocs.APP_LOGIN_CSRF_RULE,
        parameters = {
            @Parameter(name = "provider", in = ParameterIn.PATH, description = "apple 또는 kakao",
                schema = @Schema(type = "string")),
            @Parameter(name = AuthApiDocs.CSRF_HEADER, in = ParameterIn.HEADER,
                description = AuthApiDocs.APP_LOGIN_CSRF_HEADER_DESCRIPTION, schema = @Schema(type = "string"))
        })
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "발급한 nonce",
            headers =
            @Header(name = "Cache-Control", description = AuthApiDocs.NO_STORE, schema = @Schema(type = "string")),
            content =
            @Content(mediaType = "application/json", schema = @Schema(implementation = AppLoginNonceResponse.class),
                examples = @ExampleObject(name = "nonce", value = AuthApiDocsExamples.APP_LOGIN_NONCE))),
        @ApiResponse(responseCode = "400", description = "CSRF 토큰 확인 실패 또는 사이트가 아닌 출처",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(name = "csrfRejected", value = AuthApiDocsExamples.CSRF_REJECTED))),
        @ApiResponse(responseCode = "404", description = "앱 로그인이 켜지지 않은 제공자",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(name = "endpointNotFound", value = AuthApiDocsExamples.ENDPOINT_NOT_FOUND)))
    })
    public ResponseEntity<AppLoginNonceResponse> nonce(
        @Parameter(hidden = true) @PathVariable("provider") String provider,
        HttpServletRequest request
    ) {
        AppLogin.Provider target = enabledProvider(provider);
        requireSiteOrigin(request);
        String nonce = nonces.issue(request, target.provider());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new AppLoginNonceResponse(nonce));
    }

    @PostMapping("/api/auth/{provider}/native")
    @Operation(operationId = "loginWithAppIdToken", summary = "앱 ID 토큰으로 로그인",
        description = """
            앱이 애플·카카오 SDK로 받은 ID 토큰으로 로그인하고 204와 인증 쿠키 두 개를 내립니다. 처음 보는 계정이면 가입시킵니다.
            웹 로그인과 같은 계정이면 같은 회원입니다. 로그인하면 로그인 중 임시 세션을 지웁니다.

            토큰은 서명(RS256)·발급자·대상(앱용 값)·만료와 nonce를 확인합니다. nonce는 `POST /api/auth/{provider}/native/nonce`로
            이 브라우저가 받은 값이어야 하고, 맞든 틀리든 한 번 쓰면 다시 쓸 수 없습니다.
            어느 하나라도 맞지 않으면 이유와 관계없이 401 `UNAUTHENTICATED`입니다. 이때는 nonce부터 다시 받습니다.
            제공자 공개키를 받지 못하면 503 `SERVICE_UNAVAILABLE`입니다.

            """ + AuthApiDocs.APP_LOGIN_CSRF_RULE,
        parameters = {
            @Parameter(name = "provider", in = ParameterIn.PATH, description = "apple 또는 kakao",
                schema = @Schema(type = "string")),
            @Parameter(name = AuthApiDocs.CSRF_HEADER, in = ParameterIn.HEADER,
                description = AuthApiDocs.APP_LOGIN_CSRF_HEADER_DESCRIPTION, schema = @Schema(type = "string"))
        })
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "로그인함. 본문 없음", content = @Content,
            headers = {
                @Header(name = "Cache-Control", description = AuthApiDocs.NO_STORE,
                    schema = @Schema(type = "string")),
                @Header(name = "Set-Cookie",
                    description = "__Host-sb_at(Max-Age 900)과 __Host-sb_rt(Max-Age는 리프레시 토큰 만료까지). "
                        + "둘 다 HttpOnly; Secure; SameSite=Lax; Path=/",
                    schema = @Schema(type = "string"))
            }),
        @ApiResponse(responseCode = "400", description = "본문에 idToken이 없음, CSRF 토큰 확인 실패 또는 사이트가 아닌 출처",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = {
                    @ExampleObject(name = "invalidRequest", value = ApiDocsExamples.INVALID_REQUEST),
                    @ExampleObject(name = "csrfRejected", value = AuthApiDocsExamples.CSRF_REJECTED)
                })),
        @ApiResponse(responseCode = "401", description = "토큰 검증 실패, nonce 없음 또는 이미 쓴 nonce",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(name = "unauthenticated", value = AuthApiDocsExamples.UNAUTHENTICATED))),
        @ApiResponse(responseCode = "404", description = "앱 로그인이 켜지지 않은 제공자",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(name = "endpointNotFound", value = AuthApiDocsExamples.ENDPOINT_NOT_FOUND))),
        @ApiResponse(responseCode = "503", description = "제공자 공개키를 받지 못함",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(name = "serviceUnavailable", value = ApiDocsExamples.SERVICE_UNAVAILABLE)))
    })
    public ResponseEntity<Void> login(
        @Parameter(hidden = true) @PathVariable("provider") String provider,
        @RequestBody(required = false) AppLoginRequest body,
        HttpServletRequest request
    ) {
        AppLogin.Provider target = enabledProvider(provider);
        requireSiteOrigin(request);
        if (body == null || body.idToken() == null || body.idToken().isBlank()) {
            throw new InvalidLoginRequestException();
        }
        LoginProvider loginProvider = target.provider();
        Optional<String> nonce = nonces.take(request, loginProvider);
        if (nonce.isEmpty()) {
            throw rejected(loginProvider, "nonce_missing");
        }
        Verification verification = target.verifier().verify(body.idToken(), nonce.get());
        String subject = switch (verification) {
            case Verification.Verified verified -> verified.subject();
            case Verification.Rejected rejected -> throw rejected(loginProvider, "invalid_token");
            case Verification.Unavailable unavailable -> throw unavailable(loginProvider);
        };
        LoginCompletion.Completed completed;
        try {
            completed = completion.complete(loginProvider, subject);
        } catch (RuntimeException exception) {
            LOGGER.error("event=user_login status=FAILED provider={} channel=app phase=member_or_token cause={}",
                nameOf(loginProvider), exception.getClass().getSimpleName());
            monitoring.login(loginProvider, LoginResult.FAILED, MemberKind.UNKNOWN);
            throw exception;
        }
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        monitoring.login(loginProvider, LoginResult.SUCCESS,
            completed.registered() ? MemberKind.NEW : MemberKind.EXISTING);
        return ResponseEntity.noContent()
            .headers(completed.cookies())
            .cacheControl(CacheControl.noStore())
            .build();
    }

    private AppLogin.Provider enabledProvider(String pathName) {
        return appLogin.provider(pathName)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private void requireSiteOrigin(HttpServletRequest request) {
        if (!appLogin.fromSite(request)) {
            throw new CsrfRejectedException();
        }
    }

    private UnauthenticatedException rejected(LoginProvider provider, String reason) {
        LOGGER.warn("event=user_login status=FAILED provider={} channel=app reason={}", nameOf(provider), reason);
        monitoring.login(provider, LoginResult.FAILED, MemberKind.UNKNOWN);
        return new UnauthenticatedException();
    }

    private ServiceUnavailableException unavailable(LoginProvider provider) {
        LOGGER.error("event=user_login status=FAILED provider={} channel=app reason=provider_keys_unavailable",
            nameOf(provider));
        monitoring.login(provider, LoginResult.FAILED, MemberKind.UNKNOWN);
        return new ServiceUnavailableException();
    }

    private static String nameOf(LoginProvider provider) {
        return provider.name().toLowerCase(Locale.ROOT);
    }
}
