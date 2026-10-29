package com.gustler.backend.api.http.login;

import com.gustler.backend.api.http.login.AppIdTokenVerifier.Verification;
import com.gustler.backend.api.http.login.AppleAppTokenClient.Exchange;
import com.gustler.backend.member.api.LoginProvider;
import com.gustler.backend.member.api.MemberIdentity;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.UUID;

/**
 * 앱에서 애플로 다시 인증한 회원을 탈퇴시킨다.
 *
 * <p>앱이 준 인가 코드를 앱 {@code client_id} 로 교환하고, 함께 받은 ID 토큰의 {@code sub} 로 다시 인증한 계정을 확인한다.
 * 그 뒤는 웹 탈퇴와 같은 {@link AppleWithdrawal} 규칙이다. 계정이 탈퇴할 회원의 것일 때만, 애플 토큰을 앱
 * {@code client_id} 로 취소한 뒤 회원을 지운다. 어느 단계든 실패하면 회원은 그대로 남는다.
 *
 * <p>ID 토큰은 우리 서버가 애플 토큰 엔드포인트에서 직접 받은 것이라 nonce 는 보지 않고 서명·발급자·대상·만료만 확인한다.
 * 코드 교환·ID 토큰 단계의 실패는 {@code auth.withdraw{provider=APPLE}} 에 {@code code_rejected}·{@code apple_unavailable}
 * 로 센다. 그 뒤 단계는 {@link AppleWithdrawal} 가 센다.
 */
final class AppleAppWithdrawal {
    private final AppleAppTokenClient tokenClient;
    private final AppIdTokenVerifier verifier;
    private final AppleWithdrawal withdrawal;
    private final MeterRegistry meters;

    AppleAppWithdrawal(
        AppleAppTokenClient tokenClient,
        AppIdTokenVerifier verifier,
        AppleWithdrawal withdrawal,
        MeterRegistry meters
    ) {
        this.tokenClient = tokenClient;
        this.verifier = verifier;
        this.withdrawal = withdrawal;
        this.meters = meters;
    }

    Result withdraw(UUID memberId, String authorizationCode) {
        Exchange exchange = tokenClient.exchange(authorizationCode);
        if (exchange instanceof Exchange.Unavailable) {
            return counted(Result.UNAVAILABLE, "apple_unavailable");
        }
        if (!(exchange instanceof Exchange.Exchanged tokens)) {
            return counted(Result.REAUTH_FAILED, "code_rejected");
        }
        Verification verification = verifier.verifyWithoutNonce(tokens.idToken());
        if (verification instanceof Verification.Unavailable) {
            return counted(Result.UNAVAILABLE, "apple_unavailable");
        }
        if (!(verification instanceof Verification.Verified verified)) {
            return counted(Result.REAUTH_FAILED, "code_rejected");
        }
        MemberIdentity reauthenticated = new MemberIdentity(LoginProvider.APPLE, verified.subject());
        if (withdrawal.withdraw(memberId, reauthenticated, Optional.of(tokens.accessToken()))) {
            return Result.WITHDRAWN;
        }
        return Result.REAUTH_FAILED;
    }

    private Result counted(Result result, String reason) {
        meters.counter("auth.withdraw", "provider", "APPLE", "result", reason).increment();
        return result;
    }

    enum Result {
        WITHDRAWN,
        /** 코드가 틀렸거나 만료됐거나, 다른 계정이거나, 애플 토큰을 취소하지 못했다. 회원은 남는다. */
        REAUTH_FAILED,
        /** 애플이 응답하지 않았다. 회원은 남는다. */
        UNAVAILABLE
    }
}
