package com.gustler.backend.api.account.controller;

import com.gustler.backend.api.account.MemberPrincipal;
import com.gustler.backend.api.account.NicknameInvalidException;
import com.gustler.backend.api.account.NicknameTakenException;
import com.gustler.backend.api.account.dto.MyProfileResponse;
import com.gustler.backend.api.account.dto.UpdateNicknameRequest;
import com.gustler.backend.api.error.UnauthenticatedException;
import com.gustler.backend.member.api.ChangeNickname;
import com.gustler.backend.member.api.NicknameChangeResult;
import com.gustler.backend.member.api.ViewMember;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로그인한 회원 본인의 정보를 조회하고 닉네임을 바꾼다.
 *
 * <p>세션이 없는 요청은 보안 필터가 401 로 거절한다. 세션은 있지만 회원이 이미 탈퇴한 경우에는 이 컨트롤러가
 * 401 로 답한다. 응답은 회원마다 다르므로 저장하지 않게 한다. {@code auth.enabled} 가 {@code true} 일 때만 등록한다.
 */
@RestController
@RequestMapping("/api/me")
@ConditionalOnProperty(name = "auth.enabled", havingValue = "true")
public class MeController {
    private final ViewMember viewMember;
    private final ChangeNickname changeNickname;

    public MeController(ViewMember viewMember, ChangeNickname changeNickname) {
        this.viewMember = viewMember;
        this.changeNickname = changeNickname;
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

    private static ResponseEntity<MyProfileResponse> noStore(MyProfileResponse body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
