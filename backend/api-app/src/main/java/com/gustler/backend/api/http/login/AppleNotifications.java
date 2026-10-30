package com.gustler.backend.api.http.login;

import com.gustler.backend.api.http.login.AppleNotificationVerifier.Verification;
import com.gustler.backend.member.api.LoginProvider;
import com.gustler.backend.member.api.MemberIdentity;
import com.gustler.backend.member.api.ProviderUnlink;
import com.gustler.backend.member.api.RefreshTokens;
import com.gustler.backend.member.api.WithdrawMember;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 애플 서버 간 알림을 회원 정보에 반영한다.
 *
 * <p>애플 계정이 지워졌으면 그 회원을 탈퇴시킨다. 애플 계정이 이미 없으니 애플 토큰 취소는 부르지 않고 연결 해제 요청도
 * 남기지 않는다. 연결을 끊었으면 끊은 시각 전에 시작한 로그인 계통을 모두 폐기해 모든 기기에서 로그아웃시키고 회원은
 * 남긴다. 끊은 뒤 다시 로그인한 기기는 늦게 온 알림이 와도 로그인을 이어 간다. 그 밖의 알림과 회원이 없는 계정의 알림은
 * 아무것도 하지 않는다. 애플은 같은 알림을 다시 보내기도 하므로, 이미 탈퇴한 회원의
 * 알림은 회원이 없는 알림과 같다.
 *
 * <p>로그에는 알림 종류와 처리 결과만 남긴다. 애플 계정 식별자와 토큰은 남기지 않는다.
 */
final class AppleNotifications {
    private static final Logger LOGGER = LoggerFactory.getLogger(AppleNotifications.class);

    private final WithdrawMember withdrawMember;
    private final RefreshTokens refreshTokens;
    private final MeterRegistry meters;

    AppleNotifications(WithdrawMember withdrawMember, RefreshTokens refreshTokens, MeterRegistry meters) {
        this.withdrawMember = withdrawMember;
        this.refreshTokens = refreshTokens;
        this.meters = meters;
    }

    void apply(AppleAccountEvent event) {
        switch (event) {
            case AppleAccountEvent.AccountDeleted(String subject) -> {
                boolean withdrawn = memberOf(subject)
                    .flatMap(member -> withdrawMember.withdraw(member, ProviderUnlink.DONE))
                    .isPresent();
                applied("account_deleted", withdrawn);
            }
            case AppleAccountEvent.ConsentRevoked(String subject, Instant revokedAt) -> {
                boolean loggedOut = memberOf(subject)
                    .map(member -> refreshTokens.revokeAllStartedBefore(member, revokedAt))
                    .isPresent();
                applied("consent_revoked", loggedOut);
            }
            case AppleAccountEvent.Ignored(String type) -> {
                count("ignored", "ignored");
                LOGGER.info("처리하지 않는 애플 서버 간 알림이다. type={}", type);
            }
        }
    }

    void rejected(Verification.Reason reason) {
        count("unknown", "rejected");
        LOGGER.warn("애플 서버 간 알림을 검증하지 못해 거절했다. reason={}", reason.name().toLowerCase(Locale.ROOT));
    }

    void unavailable() {
        count("unknown", "unavailable");
        LOGGER.error("애플 공개키를 받지 못해 서버 간 알림을 처리하지 못했다");
    }

    private Optional<UUID> memberOf(String subject) {
        return withdrawMember.memberOf(new MemberIdentity(LoginProvider.APPLE, subject));
    }

    private void applied(String event, boolean memberFound) {
        String result = memberFound ? "applied" : "no_member";
        count(event, result);
        LOGGER.info("애플 서버 간 알림을 처리했다. event={} result={}", event, result);
    }

    private void count(String event, String result) {
        meters.counter("auth.notification", "provider", "APPLE", "event", event, "result", result).increment();
    }
}
