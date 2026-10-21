package com.gustler.backend.api.http.login;

import com.gustler.backend.member.api.MemberIdentity;
import com.gustler.backend.member.api.ProviderUnlink;
import com.gustler.backend.member.api.WithdrawMember;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 애플로 다시 인증한 회원을 탈퇴시킨다.
 *
 * <p>다시 인증한 애플 계정이 탈퇴하려는 회원의 계정과 같을 때만 진행한다. 다르면 아무것도 지우지 않는다. 다른 사람의 애플
 * 계정으로 인증해 남의 회원을 지우거나, 회원이 아닌 계정을 새로 가입시키는 일을 막는다. 같으면 애플 토큰을 먼저 취소하고 회원을
 * 지운다. 취소에 실패해도 회원은 지우고, 다시 시도할 토큰이 없으므로 사람이 확인하도록 기록만 남긴다.
 */
final class AppleWithdrawal {
    private static final Logger LOGGER = LoggerFactory.getLogger(AppleWithdrawal.class);

    private final WithdrawMember withdrawMember;
    private final AppleRevokeClient revokeClient;
    private final MeterRegistry meters;

    AppleWithdrawal(WithdrawMember withdrawMember, AppleRevokeClient revokeClient, MeterRegistry meters) {
        this.withdrawMember = withdrawMember;
        this.revokeClient = revokeClient;
        this.meters = meters;
    }

    /** 탈퇴했으면 {@code true}, 다시 인증한 계정이 이 회원의 것이 아니어서 그만뒀으면 {@code false} 다. */
    boolean withdraw(UUID memberId, MemberIdentity reauthenticated, Optional<String> providerAccessToken) {
        Optional<MemberIdentity> identity = withdrawMember.identityOf(memberId);
        if (identity.isEmpty() || !identity.get().equals(reauthenticated)) {
            meters.counter("auth.withdraw", "provider", "APPLE", "result", "identity_mismatch").increment();
            LOGGER.warn("다시 인증한 애플 계정이 탈퇴하려는 회원의 계정이 아니어서 탈퇴하지 않았다");
            return false;
        }
        final boolean revoked = providerAccessToken.map(revokeClient::revoke).orElse(false);
        withdrawMember.withdraw(memberId, unlinkOf(revoked));
        meters.counter("auth.withdraw", "provider", "APPLE", "result", resultOf(revoked)).increment();
        if (!revoked) {
            LOGGER.warn("애플 토큰을 취소하지 못했지만 탈퇴는 마쳤다. 사람이 확인할 대상으로 남겼다");
        }
        return true;
    }

    private static ProviderUnlink unlinkOf(boolean revoked) {
        if (revoked) {
            return ProviderUnlink.DONE;
        }
        return ProviderUnlink.FAILED;
    }

    private static String resultOf(boolean revoked) {
        if (revoked) {
            return "done";
        }
        return "revoke_failed";
    }
}
