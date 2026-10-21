package com.gustler.backend.api.account.controller;

import com.gustler.backend.api.account.MemberPrincipal;
import com.gustler.backend.api.account.NicknameInvalidException;
import com.gustler.backend.api.account.NicknameTakenException;
import com.gustler.backend.api.account.NotAppleMemberException;
import com.gustler.backend.api.account.ReauthRequiredException;
import com.gustler.backend.api.account.dto.AppleWithdrawalRequest;
import com.gustler.backend.api.account.dto.AppleWithdrawalResponse;
import com.gustler.backend.api.account.dto.MyProfileResponse;
import com.gustler.backend.api.account.dto.UpdateNicknameRequest;
import com.gustler.backend.api.account.token.AuthCookies;
import com.gustler.backend.api.account.token.WithdrawalTickets;
import com.gustler.backend.api.account.unlink.ProviderUnlinkRunner;
import com.gustler.backend.api.error.UnauthenticatedException;
import com.gustler.backend.member.api.ChangeNickname;
import com.gustler.backend.member.api.LoginProvider;
import com.gustler.backend.member.api.MemberIdentity;
import com.gustler.backend.member.api.NicknameChangeResult;
import com.gustler.backend.member.api.ProviderUnlink;
import com.gustler.backend.member.api.ViewMember;
import com.gustler.backend.member.api.WithdrawMember;
import com.gustler.backend.member.api.WithdrawnMember;
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
 * 로그인한 회원 본인의 정보를 조회하고 닉네임을 바꾸고 탈퇴한다.
 *
 * <p>유효한 액세스 토큰이 없는 요청은 보안 필터가 401 로 거절한다. 토큰은 유효하지만 회원이 이미 탈퇴한 경우에는 이
 * 컨트롤러가 401 로 답한다. 액세스 토큰은 만료 전까지 취소할 수 없으므로 이 확인이 탈퇴를 바로 반영한다.
 * 응답은 회원마다 다르므로 저장하지 않게 한다. {@code auth.enabled} 가 {@code true} 일 때만 등록한다.
 *
 * <p>탈퇴는 카카오 회원만 여기서 바로 한다. 회원을 지운 뒤 카카오 연결 해제를 한 번 시도하고, 실패하면 주기 작업이 다시
 * 시도한다. 해제 실패로 탈퇴를 되돌리지 않는다. 애플 회원은 애플 연결을 끊는 데 애플이 새로 발급한 토큰이 필요해 409
 * {@code REAUTH_REQUIRED} 로 답한다. 애플 회원은 {@code POST /api/me/withdrawal/apple} 로 짧은 수명의 탈퇴 티켓이 든 시작
 * 주소를 받아 그 주소로 이동한다. 이 요청은 다른 변경 요청처럼 CSRF 로 보호되므로 다른 사이트가 탈퇴를 시작시킬 수 없다.
 */
@RestController
@RequestMapping("/api/me")
@ConditionalOnProperty(name = "auth.enabled", havingValue = "true")
public class MeController {
    private static final String APPLE_WITHDRAWAL_START = "/api/auth/apple/start";

    private final ViewMember viewMember;
    private final ChangeNickname changeNickname;
    private final WithdrawMember withdrawMember;
    private final ProviderUnlinkRunner unlinkRunner;
    private final AuthCookies authCookies;
    private final WithdrawalTickets withdrawalTickets;

    public MeController(
        ViewMember viewMember,
        ChangeNickname changeNickname,
        WithdrawMember withdrawMember,
        ProviderUnlinkRunner unlinkRunner,
        AuthCookies authCookies,
        WithdrawalTickets withdrawalTickets
    ) {
        this.viewMember = viewMember;
        this.changeNickname = changeNickname;
        this.withdrawMember = withdrawMember;
        this.unlinkRunner = unlinkRunner;
        this.authCookies = authCookies;
        this.withdrawalTickets = withdrawalTickets;
    }

    @GetMapping
    public ResponseEntity<MyProfileResponse> profile(@AuthenticationPrincipal MemberPrincipal principal) {
        return noStore(viewMember.view(principal.publicId())
            .map(MyProfileResponse::from)
            .orElseThrow(UnauthenticatedException::new));
    }

    @PatchMapping
    public ResponseEntity<MyProfileResponse> changeNickname(
        @AuthenticationPrincipal MemberPrincipal principal,
        @RequestBody UpdateNicknameRequest request
    ) {
        return switch (changeNickname.change(principal.publicId(), request.nickname())) {
            case NicknameChangeResult.Changed changed -> noStore(MyProfileResponse.from(changed.profile()));
            case NicknameChangeResult.Invalid invalid -> throw new NicknameInvalidException(invalid.violation());
            case NicknameChangeResult.Taken taken -> throw new NicknameTakenException();
            case NicknameChangeResult.MemberNotFound notFound -> throw new UnauthenticatedException();
        };
    }

    @DeleteMapping
    public ResponseEntity<Void> withdraw(@AuthenticationPrincipal MemberPrincipal principal) {
        MemberIdentity identity = withdrawMember.identityOf(principal.publicId())
            .orElseThrow(UnauthenticatedException::new);
        if (identity.provider() == LoginProvider.APPLE) {
            throw new ReauthRequiredException();
        }
        WithdrawnMember withdrawn = withdrawMember.withdraw(principal.publicId(), ProviderUnlink.PENDING)
            .orElseThrow(UnauthenticatedException::new);
        withdrawn.pendingUnlink().ifPresent(unlinkRunner::attempt);
        HttpHeaders headers = new HttpHeaders();
        authCookies.clear(headers);
        return ResponseEntity.noContent().headers(headers).cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/withdrawal/apple")
    public ResponseEntity<AppleWithdrawalResponse> startAppleWithdrawal(
        @AuthenticationPrincipal MemberPrincipal principal,
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
