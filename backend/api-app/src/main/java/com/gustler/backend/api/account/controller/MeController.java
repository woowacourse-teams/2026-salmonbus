package com.gustler.backend.api.account.controller;

import com.gustler.backend.api.account.MemberPrincipal;
import com.gustler.backend.api.account.NicknameInvalidException;
import com.gustler.backend.api.account.NicknameTakenException;
import com.gustler.backend.api.account.NotAppleMemberException;
import com.gustler.backend.api.account.ReauthRequiredException;
import com.gustler.backend.api.account.analytics.AmplitudeDeletionProcessor;
import com.gustler.backend.api.account.dto.AppleWithdrawalRequest;
import com.gustler.backend.api.account.dto.AppleWithdrawalResponse;
import com.gustler.backend.api.account.dto.MyProfileResponse;
import com.gustler.backend.api.account.dto.UpdateNicknameRequest;
import com.gustler.backend.api.account.token.AuthCookies;
import com.gustler.backend.api.account.token.WithdrawalTickets;
import com.gustler.backend.api.account.unlink.ProviderUnlinkRunner;
import com.gustler.backend.api.docs.ApiDocsExamples;
import com.gustler.backend.api.docs.ApiDocsTags;
import com.gustler.backend.api.docs.AuthApiDocs;
import com.gustler.backend.api.docs.AuthApiDocsExamples;
import com.gustler.backend.api.error.ErrorResponse;
import com.gustler.backend.api.error.UnauthenticatedException;
import com.gustler.backend.member.api.ChangeNickname;
import com.gustler.backend.member.api.LoginProvider;
import com.gustler.backend.member.api.MemberIdentity;
import com.gustler.backend.member.api.NicknameChangeResult;
import com.gustler.backend.member.api.ProviderUnlink;
import com.gustler.backend.member.api.RefreshTokens;
import com.gustler.backend.member.api.ViewMember;
import com.gustler.backend.member.api.WithdrawMember;
import com.gustler.backend.member.api.WithdrawnMember;
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
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 로그인한 회원 본인의 정보를 조회하고 닉네임을 바꾸고, 모든 기기에서 로그아웃하고 탈퇴한다.
 *
 * <p>유효한 액세스 토큰이 없는 요청은 보안 필터가 401 로 거절한다. 토큰은 유효하지만 회원이 이미 탈퇴한 경우에는 이
 * 컨트롤러가 401 로 답한다. 액세스 토큰은 만료 전까지 취소할 수 없으므로 이 확인이 탈퇴를 바로 반영한다.
 * 응답은 회원마다 다르므로 저장하지 않게 한다. {@code auth.enabled} 가 {@code true} 일 때만 등록한다.
 *
 * <p>탈퇴는 카카오 회원만 여기서 바로 한다. 회원을 지운 뒤 카카오 연결 해제를 한 번 시도하고, 실패하면 주기 작업이 다시
 * 시도한다. 해제 실패로 탈퇴를 되돌리지 않는다. 애플 회원은 애플 연결을 끊는 데 애플이 새로 발급한 토큰이 필요해 409
 * {@code REAUTH_REQUIRED} 로 답한다. 애플 회원은 {@code POST /api/me/withdrawal/apple} 로 짧은 수명의 탈퇴 티켓이 든 시작
 * 주소를 받아 그 주소로 이동한다. 이 요청은 다른 변경 요청처럼 CSRF 로 보호되므로 다른 사이트가 탈퇴를 시작시킬 수 없다.
 *
 * <p>모든 기기에서 로그아웃하면 회원의 리프레시 토큰 계통을 모두 폐기한다. 다른 기기는 다음 갱신에서 거절되지만, 이미 발급된
 * 액세스 토큰은 만료까지 쓸 수 있다.
 */
@RestController
@Tag(name = ApiDocsTags.MEMBER)
@RequestMapping("/api/me")
@ConditionalOnProperty(name = "auth.enabled", havingValue = "true")
public class MeController {
    private static final String APPLE_WITHDRAWAL_START = "/api/auth/apple/start";

    private final ViewMember viewMember;
    private final ChangeNickname changeNickname;
    private final WithdrawMember withdrawMember;
    private final ProviderUnlinkRunner unlinkRunner;
    private final AmplitudeDeletionProcessor analytics;
    private final AuthCookies authCookies;
    private final WithdrawalTickets withdrawalTickets;
    private final RefreshTokens refreshTokens;

    public MeController(
        ViewMember viewMember,
        ChangeNickname changeNickname,
        WithdrawMember withdrawMember,
        ProviderUnlinkRunner unlinkRunner,
        AmplitudeDeletionProcessor analytics,
        AuthCookies authCookies,
        WithdrawalTickets withdrawalTickets,
        RefreshTokens refreshTokens
    ) {
        this.viewMember = viewMember;
        this.changeNickname = changeNickname;
        this.withdrawMember = withdrawMember;
        this.unlinkRunner = unlinkRunner;
        this.analytics = analytics;
        this.authCookies = authCookies;
        this.withdrawalTickets = withdrawalTickets;
        this.refreshTokens = refreshTokens;
    }

    @GetMapping
    @Operation(operationId = "getMyProfile", summary = "내 정보",
        description = """
            로그인한 회원의 공개 ID·닉네임·로그인 제공자·가입 시각을 반환합니다.
            토큰이 없거나 만료·위조됐거나 회원이 탈퇴했으면 401 `UNAUTHENTICATED`입니다.
            액세스 토큰이 만료됐으면 `POST /api/auth/refresh`로 갱신한 뒤 다시 요청합니다.
            """ + AuthApiDocs.ACCESS_TOKEN_RULE)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "내 정보",
            headers =
            @Header(name = "Cache-Control", description = AuthApiDocs.NO_STORE, schema = @Schema(type = "string")),
            content =
            @Content(mediaType = "application/json", schema = @Schema(implementation = MyProfileResponse.class),
                examples = @ExampleObject(name = "profile", value = AuthApiDocsExamples.PROFILE))),
        @ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 만료·위조됨, 또는 탈퇴한 회원",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(name = "unauthenticated", value = AuthApiDocsExamples.UNAUTHENTICATED)))
    })
    public ResponseEntity<MyProfileResponse> profile(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal principal
    ) {
        return noStore(viewMember.view(principal.publicId())
            .map(MyProfileResponse::from)
            .orElseThrow(UnauthenticatedException::new));
    }

    @PatchMapping
    @Operation(operationId = "changeMyNickname", summary = "닉네임 변경",
        description = """
            닉네임을 바꾸고 바뀐 내 정보를 반환합니다. 앞뒤 공백을 지운 값이 닉네임입니다.
            - 길이: 2자 이상 10자 이하. 없거나 비어 있으면 `NICKNAME_LENGTH`
            - 문자: 한글 완성형·영문·숫자만. 어기면 `NICKNAME_CHARACTERS`
            - 금칙어: 운영자로 오인할 수 있는 단어를 포함하면 `NICKNAME_RESERVED`
            - 중복: 다른 회원이 쓰는 닉네임이면 영문 대소문자를 구분하지 않고 409 `NICKNAME_TAKEN`

            """ + AuthApiDocs.ACCESS_TOKEN_RULE + " " + AuthApiDocs.CSRF_RULE,
        parameters = @Parameter(name = AuthApiDocs.CSRF_HEADER, in = ParameterIn.HEADER,
            description = AuthApiDocs.CSRF_HEADER_DESCRIPTION, schema = @Schema(type = "string")))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "바뀐 내 정보",
            headers =
            @Header(name = "Cache-Control", description = AuthApiDocs.NO_STORE, schema = @Schema(type = "string")),
            content =
            @Content(mediaType = "application/json", schema = @Schema(implementation = MyProfileResponse.class),
                examples = @ExampleObject(name = "profile", value = AuthApiDocsExamples.PROFILE))),
        @ApiResponse(responseCode = "400", description = "닉네임 규칙 위반, CSRF 토큰 확인 실패 또는 본문 형식 오류",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = {
                    @ExampleObject(name = "nicknameLength", value = AuthApiDocsExamples.NICKNAME_LENGTH),
                    @ExampleObject(name = "nicknameCharacters", value = AuthApiDocsExamples.NICKNAME_CHARACTERS),
                    @ExampleObject(name = "nicknameReserved", value = AuthApiDocsExamples.NICKNAME_RESERVED),
                    @ExampleObject(name = "csrfRejected", value = AuthApiDocsExamples.CSRF_REJECTED),
                    @ExampleObject(name = "invalidRequest", value = ApiDocsExamples.INVALID_REQUEST)
                })),
        @ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 만료·위조됨, 또는 탈퇴한 회원",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(name = "unauthenticated", value = AuthApiDocsExamples.UNAUTHENTICATED))),
        @ApiResponse(responseCode = "409", description = "다른 회원이 쓰는 닉네임",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(name = "nicknameTaken", value = AuthApiDocsExamples.NICKNAME_TAKEN)))
    })
    public ResponseEntity<MyProfileResponse> changeNickname(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal principal,
        @RequestBody UpdateNicknameRequest request
    ) {
        return switch (changeNickname.change(principal.publicId(), request.nickname())) {
            case NicknameChangeResult.Changed changed -> noStore(MyProfileResponse.from(changed.profile()));
            case NicknameChangeResult.Invalid invalid -> throw new NicknameInvalidException(invalid.violation());
            case NicknameChangeResult.Taken taken -> throw new NicknameTakenException();
            case NicknameChangeResult.MemberNotFound notFound -> throw new UnauthenticatedException();
        };
    }

    @DeleteMapping("/sessions")
    @Operation(operationId = "logoutAllSessions", summary = "모든 기기에서 로그아웃",
        description = """
            이 회원이 로그인한 모든 기기의 리프레시 토큰을 폐기하고, 이 요청의 인증 쿠키 두 개를 지운 뒤 204로 답합니다.
            다른 기기는 다음 `POST /api/auth/refresh`에서 401을 받습니다.
            이미 발급한 액세스 토큰은 만료(최대 15분)까지 취소되지 않으므로, 쿠키를 쓰지 않는 클라이언트는 보관한 토큰을 직접 버립니다.

            """ + AuthApiDocs.ACCESS_TOKEN_RULE + " " + AuthApiDocs.CSRF_RULE,
        parameters = @Parameter(name = AuthApiDocs.CSRF_HEADER, in = ParameterIn.HEADER,
            description = AuthApiDocs.CSRF_HEADER_DESCRIPTION, schema = @Schema(type = "string")))
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "모든 기기에서 로그아웃함. 본문 없음", content = @Content,
            headers = {
                @Header(name = "Cache-Control", description = AuthApiDocs.NO_STORE,
                    schema = @Schema(type = "string")),
                @Header(name = "Set-Cookie", description = AuthApiDocs.CLEARED_COOKIES,
                    schema = @Schema(type = "string"))
            }),
        @ApiResponse(responseCode = "400", description = "CSRF 토큰 확인 실패",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(name = "csrfRejected", value = AuthApiDocsExamples.CSRF_REJECTED))),
        @ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 만료·위조됨, 또는 탈퇴한 회원",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(name = "unauthenticated", value = AuthApiDocsExamples.UNAUTHENTICATED)))
    })
    public ResponseEntity<Void> logoutAllSessions(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal principal
    ) {
        viewMember.view(principal.publicId()).orElseThrow(UnauthenticatedException::new);
        refreshTokens.revokeAll(principal.publicId());
        HttpHeaders headers = new HttpHeaders();
        authCookies.clear(headers);
        return ResponseEntity.noContent().headers(headers).cacheControl(CacheControl.noStore()).build();
    }

    @DeleteMapping
    @Operation(operationId = "withdraw", summary = "회원 탈퇴(카카오)",
        description = """
            카카오 회원은 이 요청으로 바로 탈퇴합니다. 회원을 지우고 인증 쿠키 두 개를 지운 뒤 204로 답합니다.
            카카오 연결 해제는 탈퇴 직후 한 번 시도하고, 실패하면 서버가 나중에 다시 시도합니다. 해제 실패로 탈퇴를 되돌리지 않습니다.

            애플 회원은 애플 계정으로 다시 인증해야 해서 409 `REAUTH_REQUIRED`입니다.
            이때는 `POST /api/me/withdrawal/apple`로 시작 주소를 받아 브라우저를 그 주소로 이동시킵니다.

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
        @ApiResponse(responseCode = "400", description = "CSRF 토큰 확인 실패",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(name = "csrfRejected", value = AuthApiDocsExamples.CSRF_REJECTED))),
        @ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 만료·위조됨, 또는 탈퇴한 회원",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(name = "unauthenticated", value = AuthApiDocsExamples.UNAUTHENTICATED))),
        @ApiResponse(responseCode = "409", description = "애플 회원. POST /api/me/withdrawal/apple로 재인증 탈퇴를 시작",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(name = "reauthRequired", value = AuthApiDocsExamples.REAUTH_REQUIRED)))
    })
    public ResponseEntity<Void> withdraw(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal principal
    ) {
        MemberIdentity identity = withdrawMember.identityOf(principal.publicId())
            .orElseThrow(UnauthenticatedException::new);
        if (identity.provider() == LoginProvider.APPLE) {
            throw new ReauthRequiredException();
        }
        WithdrawnMember withdrawn = withdrawMember.withdraw(principal.publicId(), ProviderUnlink.PENDING)
            .orElseThrow(UnauthenticatedException::new);
        withdrawn.pendingUnlink().ifPresent(unlinkRunner::attempt);
        analytics.attempt(principal.publicId());
        HttpHeaders headers = new HttpHeaders();
        authCookies.clear(headers);
        return ResponseEntity.noContent().headers(headers).cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/withdrawal/apple")
    @Operation(operationId = "startAppleWithdrawal", summary = "애플 회원 탈퇴 시작 주소 발급",
        description = """
            애플 회원은 애플 계정으로 다시 인증해야 탈퇴할 수 있습니다.
            이 요청은 5분 동안 유효한 탈퇴 티켓이 든 시작 주소를 반환하고, 클라이언트는 브라우저를 그 주소로 이동시킵니다.
            - 탈퇴하면 `/?withdrawn=1`로 돌아오고 인증 쿠키가 지워집니다
            - 사용자가 취소하면 `returnTo`에 `withdraw=cancelled`, 그 밖의 실패는 `withdraw=failed`가 붙어 돌아옵니다
            - 애플 토큰 취소에 실패하면 회원을 지우지 않고 `withdraw=failed`로 돌아옵니다. 인증 쿠키가 남아 다시 시도할 수 있습니다
            - `returnTo`는 `/` 또는 `/routes/{9자리}`(쿼리 `direction`·`stopId`만)이며, 그 밖의 값은 `/`로 바뀝니다

            본문은 생략할 수 있습니다. 카카오 회원이면 400 `INVALID_REQUEST`입니다.
            """ + AuthApiDocs.ACCESS_TOKEN_RULE + " " + AuthApiDocs.CSRF_RULE,
        parameters = @Parameter(name = AuthApiDocs.CSRF_HEADER, in = ParameterIn.HEADER,
            description = AuthApiDocs.CSRF_HEADER_DESCRIPTION, schema = @Schema(type = "string")))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "브라우저가 이동할 시작 주소",
            headers =
            @Header(name = "Cache-Control", description = AuthApiDocs.NO_STORE, schema = @Schema(type = "string")),
            content =
            @Content(mediaType = "application/json", schema = @Schema(implementation = AppleWithdrawalResponse.class),
                examples = @ExampleObject(name = "startUrl", value = AuthApiDocsExamples.APPLE_WITHDRAWAL))),
        @ApiResponse(responseCode = "400", description = "카카오 회원, CSRF 토큰 확인 실패 또는 본문 형식 오류",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = {
                    @ExampleObject(name = "invalidRequest", value = ApiDocsExamples.INVALID_REQUEST),
                    @ExampleObject(name = "csrfRejected", value = AuthApiDocsExamples.CSRF_REJECTED)
                })),
        @ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 만료·위조됨, 또는 탈퇴한 회원",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(name = "unauthenticated", value = AuthApiDocsExamples.UNAUTHENTICATED)))
    })
    public ResponseEntity<AppleWithdrawalResponse> startAppleWithdrawal(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal principal,
        @RequestBody(required = false) AppleWithdrawalRequest request
    ) {
        MemberIdentity identity = withdrawMember.identityOf(principal.publicId())
            .orElseThrow(UnauthenticatedException::new);
        if (identity.provider() != LoginProvider.APPLE) {
            throw new NotAppleMemberException();
        }
        Map<String, String> values = new HashMap<>();
        values.put("ticket", withdrawalTickets.issue(principal.publicId()));
        UriComponentsBuilder startUrl = UriComponentsBuilder.fromPath(APPLE_WITHDRAWAL_START)
            .queryParam("intent", "withdraw")
            .queryParam("ticket", "{ticket}");
        if (request != null && request.returnTo() != null) {
            values.put("returnTo", request.returnTo());
            startUrl.queryParam("returnTo", "{returnTo}");
        }
        String url = startUrl.encode().buildAndExpand(values).toUriString();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new AppleWithdrawalResponse(url));
    }

    private static ResponseEntity<MyProfileResponse> noStore(MyProfileResponse body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
