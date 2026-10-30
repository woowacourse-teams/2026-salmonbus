package com.gustler.backend.api.http.login;

import java.time.Instant;

/** 애플이 서버 간 알림으로 알린 애플 계정의 변화. {@code subject} 는 애플 계정 식별자({@code sub})다. */
sealed interface AppleAccountEvent {
    record AccountDeleted(String subject) implements AppleAccountEvent {
    }

    /**
     * 이용자가 {@code revokedAt} 에 애플 설정에서 우리 서비스와의 연결을 끊었다. 애플은 알림을 늦게 보내거나 다시 보내기도
     * 하므로, 이 알림이 끊어야 하는 로그인은 그 시각 전에 시작한 것뿐이다.
     */
    record ConsentRevoked(String subject, Instant revokedAt) implements AppleAccountEvent {
    }

    /** 처리하지 않는 알림. 이메일 전달 설정 변경과 모르는 종류다. */
    record Ignored(String type) implements AppleAccountEvent {
    }

    /** {@code account-delete} 는 애플 문서의 옛 표기다. */
    static AppleAccountEvent of(String type, String subject, Instant occurredAt) {
        return switch (type) {
            case "account-deleted", "account-delete" -> new AccountDeleted(subject);
            case "consent-revoked" -> new ConsentRevoked(subject, occurredAt);
            default -> new Ignored(type);
        };
    }
}
