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
 * 지운다. 취소하지 못하면(취소할 토큰이 없을 때 포함) 회원을 지우지 않는다. 서버에는 나중에 애플 연결을 다시 끊을 토큰이
 * 없으므로, 회원을 남겨 두고 사용자가 다시 탈퇴하게 한다.
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

    /**
     * 탈퇴했으면 {@code true} 다. 다시 인증한 계정이 이 회원의 것이 아니거나 애플 토큰을 취소하지 못해 그만뒀으면
     * {@code false} 이고, 회원은 그대로 남는다.
     */
    boolean withdraw(UUID memberId, MemberIdentity reauthenticated, Optional<String> providerAccessToken) {
        Optional<MemberIdentity> identity = withdrawMember.identityOf(memberId);
        if (identity.isEmpty() || !identity.get().equals(reauthenticated)) {
            count("identity_mismatch");
            LOGGER.warn("다시 인증한 애플 계정이 탈퇴하려는 회원의 계정이 아니어서 탈퇴하지 않았다");
            return false;
        }
        if (!providerAccessToken.map(revokeClient::revoke).orElse(false)) {
            count("revoke_failed");
            LOGGER.warn("애플 토큰을 취소하지 못해 탈퇴하지 않았다");
            return false;
        }
        withdrawMember.withdraw(memberId, ProviderUnlink.DONE);
        count("done");
        return true;
    }

    private void count(String result) {
        meters.counter("auth.withdraw", "provider", "APPLE", "result", result).increment();
    }
}
